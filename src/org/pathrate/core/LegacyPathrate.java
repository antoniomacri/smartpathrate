package org.pathrate.core;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;

import org.pathrate.core.MathHelper.Mode;

public class LegacyPathrate implements IPathrate
{
	private static final int tcpSenderPort = 48699;
	private static final int udpReceiverPort = 48698;
	private static final int udpBufferSize = 212536;
	private static final int MAX_PACKET_SIZE = 16384;
	private static final int MAX_CONSECUTIVE_LOSSES = 30;
	private static final int MIN_TRAIN_SPACING = 500;
	private static final int NUMBER_OF_TRAINS_P1 = 1000;
	private static final int NUMBER_OF_TRAINS_P2 = 500;
	private static final int MAX_NUMBER_OF_MODES = 1000;
	private static final int MIN_PACKET_SIZE_P1 = 572;
	private static final int MAX_TRAIN_LEN = 50;
	private static final int IGNORE_LIM_FACTOR = 4;
	private static final double COEF_VAR_THR = 0.05;
	private static final double ADR_REDCT_THR = 0.90;

	private enum Command {
		TRAIN_LEN,
		NO_TRAINS,
		GAME_OVER,
		ACK_TRAIN,
		PCK_LEN,
		MAX_PCK_LEN,
		SEND,
		CONTINUE,
		TRAIN_SPACING,
		SENT_TRAIN,
		NEG_ACK_TRAIN,
		ERROR,
	}

	public CapacityData capacityData;

	private DatagramSocket udpSocket;
	private Socket tcpSocket;
	private InputStream reader;
	private OutputStream writer;
	private ISink sink;

	public void install(ISink sink, IConnectionSpeedProvider connectionSpeedProvider)
	{
		this.sink = sink;
	}

	public void startAsSender(ICancelTask task) throws IOException, InterruptedException
	{
		sink.info("Starting as sender...");

		ServerSocket listener = new ServerSocket(tcpSenderPort);

		udpSocket = new DatagramSocket();
		udpSocket.setSendBufferSize(udpBufferSize);

		int roundId = 1, packetSize = 1500, maxPacketSize;
		int trainLength = 3, numberOfTrains = 1, trainSpacing = 1000;
		boolean iterative = false;
		int ctr_code_cmnd = 0, ctr_code_data = 0;

		byte[] fourByteBuffer = new byte[4];

		do {
			sink.info("Waiting for receiver to establish control stream...");

			// Wait until receiver attempts to connect, starting new measurement cycle
			try {
				tcpSocket = listener.accept();
				sink.info("Connected to " + tcpSocket.getInetAddress() + ".");
				try {
					reader = tcpSocket.getInputStream();
					writer = tcpSocket.getOutputStream();

					// Form receiving UDP address
					InetAddress udpRemoteAddress = tcpSocket.getInetAddress();

					// Bounce a number of empty messages back to the receiver,
					// in order to measure RTT
					sink.info("Measuring RTT...");
					// We receive and resend 10*sizeof(long) bytes
					// (we suppose C version is compiled on a 32-bit system)
					for (int i = 0; i < 10; i++) {
						reader.read(fourByteBuffer);
						writer.write(fourByteBuffer);
						writer.flush();
					}

					// Create random packet payload to deal with links that do payload compression
					sink.info("Creating random payload...");
					Random random = new Random();
					byte[] packBuffer = new byte[MAX_PACKET_SIZE];
					random.nextBytes(packBuffer);

					/*
					 * loop:
					 *  1) Get control messages for next phase (until SEND command)
					 *  2) Send packets for that phase (until complete, or CONTINUE command)
					 */

					int trains_lost = 0;
					boolean done = false, reset_flag = false;

					while (!done) {
						sink.info("Phase I started.");
						do {
							if (task.isCancelled()) {
								done = true;
								break;
							}
							// Wait until a control message arrives (unless if reset_flag=1)
							// If reset_flag=1, a control message is already here
							if (!reset_flag) {
								// Get the command and the data fields from the control message
								reader.read(fourByteBuffer, 0, 4);
								ctr_code_cmnd = fourByteBuffer[3];
								ctr_code_data = ByteBuffer.wrap(fourByteBuffer).order(ByteOrder.BIG_ENDIAN).getInt() >> 8;
							}
							reset_flag = false;

							if (ctr_code_cmnd >= Command.values().length) {
								ctr_code_cmnd = Command.ERROR.ordinal();
							}
							sink.info(String.format("[%08X] %s(%d): ",
									ByteBuffer.wrap(fourByteBuffer).order(ByteOrder.BIG_ENDIAN).getInt(),
									Command.values()[ctr_code_cmnd], ctr_code_data));

							switch (Command.values()[ctr_code_cmnd]) {
							// Get maximum packet size from receiver
							case MAX_PCK_LEN:
								// TODO: What is MAX_PCK_LEN sent for?
								maxPacketSize = ctr_code_data;
								sink.info("Maximum packet size: " + (maxPacketSize + 28) + " bytes.");
								break;

							// Get packet size from receiver
							case PCK_LEN:
								packetSize = ctr_code_data;
								sink.info("Packet size: " + (packetSize + 28) + " bytes.");
								break;

							// Get train length from receiver
							case TRAIN_LEN:
								trainLength = ctr_code_data;
								sink.info("New train length: " + trainLength + ".");
								break;

							// Get number of trains from receiver
							case NO_TRAINS:
								// no_trains = ctr_code_data;
								numberOfTrains = 1;
								sink.info("New number of trains: " + numberOfTrains + ".");
								break;

							// End of measurements
							case GAME_OVER:
								sink.info("Receiver terminates measurements.");
								tcpSocket.close();
								Thread.sleep(1000);
								done = true;
								break;

							// Skip sending trains and go to next input phase of
							// control messages
							case CONTINUE:
								sink.info("Continue with next round of measurements.");
								break;

							// An ACK for a packet train; ignore at this point
							case NEG_ACK_TRAIN:
								sink.info("Redundant NEG_ACK.");
								break;

							case ACK_TRAIN:
								sink.info("Redundant ACK.");
								break;

							// Train spacing between successive packet
							// pairs/trains
							case TRAIN_SPACING:
								trainSpacing = ctr_code_data; // msec
								sink.info("Time period between packet pairs/trains: " + trainSpacing + " msec.");
								break;

							// Start sending the packet trains with the
							// specified
							// packet size, train length, and number of trains
							case SEND:
								roundId = ctr_code_data;
								sink.info("New round number: " + roundId + ".");
								break;

							default:
								sink.info("Unknown control message... aborting.");
								done = true;
							}
						} while (ctr_code_cmnd != Command.SEND.ordinal() && !done);

						sink.info("Phase II started.");
						if (ctr_code_cmnd == Command.SEND.ordinal()) {
							/*
							 * Send <no_trains> of length <train_len> with
							 * packets of size <pack_sz>. NOTE: We always send
							 * one more packet in the train. The first packet
							 * (and the corresponding spacing) is ignored,
							 * because the processing of that packet takes
							 * longer (due to cache misses). That first packet
							 * has pack_id=0.
							 */

							sink.info(String.format("Sending %d trains of 1+%d packets of %d bytes...\n",
									numberOfTrains, trainLength, packetSize));

							reset_flag = false;
							int train_no = 0, trains_ackd = 0, train_id = 0;
							do {
								/*
								 * Send train of <train_len> packets. Each packet carries a packet id (unique in each
								 * train), a train id (unique in each round), and a round id (unique in the entire
								 * execution).
								 */
								train_id++;
								ByteBuffer buffer = ByteBuffer.wrap(packBuffer).order(ByteOrder.BIG_ENDIAN);
								buffer.putInt(0);
								buffer.putInt(train_id);
								buffer.putInt(roundId);
								for (int pack_id = 0; pack_id <= trainLength; pack_id++) {
									buffer.position(0);
									buffer.putInt(pack_id);
									DatagramPacket packet = new DatagramPacket(packBuffer, 0, packetSize,
											udpRemoteAddress, udpReceiverPort);
									udpSocket.send(packet);
								}
								train_no++;

								// Introduce some delay between consecutive packet trains
								Thread.sleep(trainSpacing);

								// Send SENT_TRAIN ctr msgs and wait for ACK/NEG_ACK message
								ByteBuffer b = ByteBuffer.wrap(fourByteBuffer).order(ByteOrder.BIG_ENDIAN);
								b.putInt(Command.SENT_TRAIN.ordinal());
								// writer.write(fourByteBuffer);
								// writer.flush();

								reader.read(fourByteBuffer, 0, 4);
								ctr_code_cmnd = fourByteBuffer[3];
								if (ctr_code_cmnd == Command.ACK_TRAIN.ordinal()) {
									// the train has been ACKed
									trains_ackd++;
									trains_lost = 0;
									sink.info("Train " + train_no + ": ACKed.");
								}
								else if (ctr_code_cmnd == Command.NEG_ACK_TRAIN.ordinal()) {
									// Received negative ack, Retransmit
									sink.info("Train " + train_no + ": Retransmit.");
									// Primitive congestion avoidance
									if (++trains_lost > MAX_CONSECUTIVE_LOSSES) {
										sink.info("Too many losses..");
										sink.info("Aborting measurements to avoid congestion \n\n");
										// Send GAME_OVER signal to rcvr on TCP,
										// then quit
										ctr_code_cmnd = Command.GAME_OVER.ordinal();
										writer.write(ctr_code_cmnd);
										writer.flush();
										Thread.sleep(1000);
										tcpSocket.close();
										done = true;
									}
								}
								else {
									// The receiver has already started a new phase, and sends different control
									// messages. Abort this phase, and start the next.
									sink.info("DEBUG:: Enter here with code" + ctr_code_cmnd);
									reset_flag = true;
									trains_lost = 0;
								}
							} while (trains_ackd < numberOfTrains && !reset_flag && !done);
						} // if (ctr_command==SEND)
					} // while (!done)
				}
				catch (IOException e) {
					e.printStackTrace();
				}
				finally {
					tcpSocket.close();
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				listener.close();
			}
		} while (iterative); // Repeat forever if in iterative mode
	}

	public void startAsReceiver(InetAddress senderAddress, ICancelTask task) throws IOException, InterruptedException
	{
		sink.info("Starting as receiver...");

		boolean quick_term = false;

		int no_trains, trains_msrd = 0, trains_rcvd = 0;
		int trains_per_size = 0, no_modes_P1 = 0, no_modes_P2 = 0, cap_mode_ind = 0, bad_tstamps = 0;
		int max_pack_sz, pack_sz;

		boolean retransmit = false, abort_phase1 = false;
		boolean[] measurs_vld_P1 = new boolean[NUMBER_OF_TRAINS_P1], measurs_vld_P2 = new boolean[NUMBER_OF_TRAINS_P2];

		byte[] pack_buf = new byte[MAX_PACKET_SIZE];

		/**
		 * in milliseconds
		 */
		int train_spacing, min_train_spacing, max_train_spacing;

		double[] measurs_P1 = new double[NUMBER_OF_TRAINS_P1], measurs_P2 = new double[NUMBER_OF_TRAINS_P2];
		double[] ord_measurs_P1 = new double[NUMBER_OF_TRAINS_P1], ord_measurs_P2 = new double[NUMBER_OF_TRAINS_P2];

		Mode curr_mode = new Mode();
		Mode[] modes_P1 = new Mode[MAX_NUMBER_OF_MODES], modes_P2 = new Mode[MAX_NUMBER_OF_MODES];

		byte[] fourByteBuffer = new byte[4];

		// Create data stream: UDP socket
		sink.info("Creating UDP socket...");
		this.udpSocket = new DatagramSocket(null); // null as SocketAddress, to create an unbound socket
		udpSocket.setReuseAddress(true);
		udpSocket.setReceiveBufferSize(udpBufferSize);
		udpSocket.bind(new InetSocketAddress(udpReceiverPort));

		// Create control stream: TCP connection
		sink.info("Creating TCP connection...");
		this.tcpSocket = new Socket();
		try {
			tcpSocket.connect(new InetSocketAddress(senderAddress, tcpSenderPort), 0);
		}
		catch (IOException e) {
			sink.error("Cannot connect. Make sure that Pathrate runs at sender.");
			return;
		}

		// Get streams
		this.reader = tcpSocket.getInputStream();
		this.writer = tcpSocket.getOutputStream();

		// Estimate round-trip time
		sink.info("Estimate round-trip time...");
		final double rtt;
		{
			long sum_rtt = 0;
			for (int i = 0; i < 10; i++) {
				long time = System.nanoTime();
				writer.write(fourByteBuffer);
				writer.flush();
				reader.read(fourByteBuffer);
				// ignore first rtt
				if (i > 0) {
					sum_rtt += (System.nanoTime() - time) / 1000;
				}
			}
			rtt = sum_rtt / 9000.0;
		}
		sink.info(String.format("Average round-trip time: %.1fms.\n", rtt));

		// If the RTT is not much smaller than the specified train spacing,
		// increase minimum train spacing based on RTT
		sink.info("Determine minimum train spacing based on RTT...");
		min_train_spacing = Math.max(MIN_TRAIN_SPACING, (int) (rtt * 1.25));
		max_train_spacing = 2 * min_train_spacing;
		train_spacing = min_train_spacing;
		send_ctr_msg(writer, Command.TRAIN_SPACING, train_spacing);

		// Cannot get MSS in Java, so we use Ethernet MTU
		max_pack_sz = 1472;
		send_ctr_msg(writer, Command.MAX_PCK_LEN, max_pack_sz);

		// Also set the minimum packet size for Phase I
		final int min_pack_sz_P1 = Math.min(MIN_PACKET_SIZE_P1, max_pack_sz);

		// Create random payload (maybe here it doesn't matter)
		Random random = new Random();
		random.nextBytes(pack_buf);

		final int no_pack_sizes = 40;
		pack_sz = max_pack_sz;

		// Measure minimum latency for kernel-to-user transfer of packet
		// The multiplicative factor 3 is based on packet processing times reported in the literature
		final double min_possible_delta = 3 * calculateMinPossibleDelta(pack_buf, pack_sz, no_pack_sizes);
		sink.info(String.format("Minimum acceptable packet pair dispersion: %.0f µs.\n", min_possible_delta));

		int max_train_len;

		// The default initial train-length in Phase I is 2 packets (packet pairs)
		final int train_len_P1 = 2;

		// Keep a unique identifier for each round in pathrate's execution. This
		// id is used to ignore between trains of previous rounds.
		int round = 1;

		// Discover maximum feasible train length in the path
		// (stop at a length that causes 3 lossy trains)
		// TODO: in realtà si ferma dopo aver ricevuto per 5 volte bad_train == 2
		sink.info("Finding maximum train length...");

		// Send packet size to sender -> maximum at this phase
		no_trains = 1;
		pack_sz = max_pack_sz;
		send_ctr_msg(writer, Command.PCK_LEN, pack_sz);

		// Initial train length
		trains_msrd = 0;

		{
			int prev_train_len = 2;
			int bad_trains = 0;
			boolean path_overflow = false;
			int train_len = train_len_P1;
			while (train_len <= MAX_TRAIN_LEN && !path_overflow) {
				// Send train length to sender
				if (!retransmit) {
					send_ctr_msg(writer, Command.TRAIN_LEN, train_len);
				}
				// Wait for a complete packet train
				long[] time_stmps = new long[train_len + 1];
				if (!retransmit) {
					round++;
				}
				int bad_train = recv_train(train_len, round, time_stmps);

				// Compute dispersion and bandwidth measurement
				if (bad_train == 2) {
					sink.debug(String.format("Bad train for length %d.", train_len));
					if (bad_trains++ > 3) {
						sink.info("Path overflow.");
						path_overflow = true;
					}
				}
				else if (bad_train == 0) {
					double delta = time_stmps[train_len] - time_stmps[1];
					double bw_msr = ((28 + pack_sz) << 3) * (train_len - 1) / delta; // Mb/s
					sink.info(String.format("Train length: %d,  delta: %f,  total size: %d -> %s.", train_len, delta,
							((28 + pack_sz) << 3) * (train_len - 1), bandWidthToString(bw_msr)));
					if (delta > min_possible_delta * (train_len - 1)) {
						// Acceptable measurement
						measurs_P1[trains_msrd++] = bw_msr;
					}
					else {
						sink.info("(ignored)");
					}

					if (delta > train_spacing * 1000) {
						// Very slow link; the packet trains take more than their
						// spacing to be transmitted
						path_overflow = true;
						// Send control message to double train spacing
						// ctr_code = CONTINUE;
						// send_ctr_msg(ctr_code);
						train_spacing = max_train_spacing;
						send_ctr_msg(writer, Command.TRAIN_SPACING, train_spacing);
					}

					// Increase train length
					if (!retransmit) {
						prev_train_len = train_len;
						if (train_len < 6)
							train_len++;
						else if (train_len < 12)
							train_len += 2;
						else
							train_len += 4;
					}
				}
			}
			retransmit = false;

			if (path_overflow || train_len > MAX_TRAIN_LEN)
				max_train_len = prev_train_len; // Undo the previous increase
			else {
				// TODO: unreachable??
				max_train_len = train_len;
			}
		}
		sink.info(String.format("Maximum train length: %d packets.", max_train_len));
		/* Tell sender to continue with next phase */
		// ctr_code = CONTINUE;
		// send_ctr_msg(ctr_code);

		/* Ravi: Test IC here and continue if its not IC */
		/* get one more train with max_train_len */

		max_train_len = 200;
		long[] time_stmps = new long[max_train_len + 1];
		send_ctr_msg(writer, Command.TRAIN_LEN, max_train_len);
		do {
			if (!retransmit) {
				round++;
			}
		} while (recv_train(max_train_len, round, time_stmps) != 0);

		boolean tmp = intr_coalescence(time_stmps, max_train_len, min_possible_delta / 3);
		if (tmp) {
			sink.info("Detected Interrupt Coalescence.");
			// gig_path(senderAddress, writer, retransmit, max_pack_sz, round++, (int) (min_possible_delta / 3));
		}

		/*
		 * Check for possible multichannel links and traffic shapers
		 */

		sink.info("Preliminary measurements with increasing packet train lengths...");

		/* Send number of trains to sender (five of them is good enough..) */
		no_trains = 7; // Ravi: No need to send this to sender... we ask for
		// each train now.

		/* Send packet size to sender -> maximum at this phase */
		pack_sz = max_pack_sz;
		send_ctr_msg(writer, Command.PCK_LEN, pack_sz);
		{
			int train_len = train_len_P1;
			do {
				/* Send train length to sender */
				if (!retransmit) {
					send_ctr_msg(writer, Command.TRAIN_LEN, train_len);
				}
				/* Tell sender to start sending packet trains */
				trains_rcvd = 0;
				do { /* for each train-length */
					/* Wait for a complete packet train */
					if (!retransmit) {
						(round)++;
					}
					int bad_train = recv_train(train_len, round, time_stmps);

					/* Compute dispersion and bandwidth measurement */
					if (bad_train == 0) {
						double delta = time_stmps[train_len] - time_stmps[1];
						double bw_msr = ((28 + pack_sz) << 3) * (train_len - 1) / delta;
						sink.info(String.format("Train length: %d -> %s\n", train_len, bandWidthToString(bw_msr)));
						if (delta > min_possible_delta * (train_len - 1)) {
							/* Acceptable measurement */
							measurs_P1[trains_msrd++] = bw_msr;
						}
						trains_rcvd++;
					}
				} while (trains_rcvd < no_trains);

				if (!retransmit) {
					train_len++;
					/* Tell sender to continue with next phase */
					// ctr_code = CONTINUE;
					// send_ctr_msg(ctr_code);
				}
			} while (train_len <= Math.min(max_train_len, 10));
		}

		/* Actual number of trains measured */
		trains_msrd--;

		/*
		 * Estimate bandwidth resolution (bin width) and check for "quick estimate"
		 */

		// Calculate average and standard deviation of last measurements,
		// ignoring the five largest and the five smallest values
		System.arraycopy(measurs_P1, 0, ord_measurs_P1, 0, trains_msrd);
		Arrays.sort(ord_measurs_P1, 0, trains_msrd);
		double avg_bw, std_dev, bw_range;
		if (trains_msrd > 30) {
			avg_bw = MathHelper.getAverage(ord_measurs_P1, 5, trains_msrd - 10);
			std_dev = MathHelper.getStandardDeviation(ord_measurs_P1, 5, trains_msrd - 10);
			int outlier_lim = (int) (trains_msrd / 10);
			bw_range = ord_measurs_P1[trains_msrd - outlier_lim - 1] - ord_measurs_P1[outlier_lim];
		}
		else {
			avg_bw = MathHelper.getAverage(ord_measurs_P1, 0, trains_msrd);
			std_dev = MathHelper.getStandardDeviation(ord_measurs_P1, 0, trains_msrd);
			bw_range = ord_measurs_P1[trains_msrd - 1] - ord_measurs_P1[0];
		}

		/* Estimate bin width based on previous measurements */
		/* Weiling: bin_wd is increased to be twice of earlier value */

		double bin_wd;
		if (bw_range < 1.0) /* less than 1Mbps */
			bin_wd = bw_range * 0.25;
		else
			bin_wd = bw_range * 0.125;

		if (bin_wd == 0) bin_wd = 20.0;
		sink.info("--> Capacity Resolution: " + bandWidthToString(bin_wd));
		/* Check for quick estimate (when measurements are not very spread) */
		if ((std_dev / avg_bw < COEF_VAR_THR) || quick_term) {
			if (quick_term)
				sink.info(" - Requested Quick Termination");
			else
				sink.info("`Quick Termination' - Sufficiently low measurement noise");
			sink.info(String.format("\n\n--> Coefficient of variation: %.3f \n", std_dev / avg_bw));
			happy_end(senderAddress, avg_bw - bin_wd / 2., avg_bw + bin_wd / 2.);
			termint(writer, 0);
		}

		/* Tell sender to continue with next phase */
		// ctr_code = CONTINUE;
		// send_ctr_msg(ctr_code);

		/*
		 * Phase I: Discover local modes with packet pair experiments
		 */
		sink.info("-- Phase I: Detect possible capacity modes --");
		Thread.sleep(1000);

		/*
		 * Phase I uses packet pairs, i.e., 2 packets (the default; it may be larger)
		 */
		{
			int train_len = train_len_P1;
			if (train_len > max_train_len) train_len = max_train_len;

			// Compute number of different packet sizes in Phase I
			int pack_incr_step = (max_pack_sz - min_pack_sz_P1) / no_pack_sizes + 1;
			pack_sz = min_pack_sz_P1;

			// Compute number of trains per packet size
			int no_trains_per_size = NUMBER_OF_TRAINS_P1 / no_pack_sizes;

			// Number of trains in Phase I
			no_trains = NUMBER_OF_TRAINS_P1;

			// Send train spacing to sender
			train_spacing = min_train_spacing;
			send_ctr_msg(writer, Command.TRAIN_SPACING, train_spacing);

			trains_msrd = 0;
			retransmit = false;
			// Compute new packet size (and repeat for several packet sizes)
			for (int i = 0; i < no_pack_sizes; i++) {
				if (!retransmit) {
					// Send packet size and train length to sender
					send_ctr_msg(writer, Command.PCK_LEN, pack_sz);
					send_ctr_msg(writer, Command.TRAIN_LEN, train_len);

					sink.info(String.format("\t-> Train length: %2d - Packet size: %4dB -> %2d%% completed\n",
							train_len, pack_sz + 28, i * 100 / no_pack_sizes));
					bad_tstamps = 0;
					trains_per_size = 0;
				}

				do {
					// Wait for a complete packet train
					if (!retransmit) {
						(round)++;
					}
					int bad_train = recv_train(train_len, round, time_stmps);

					// Compute dispersion and bandwidth measurement
					if (bad_train == 0) {
						double delta = time_stmps[train_len] - time_stmps[1];
						double bw_msr = ((28 + pack_sz) << 3) * (train_len - 1) / delta;
						sink.info(String.format("\tMeasurement-%d: %s (%.0f µsec)", trains_per_size + 1,
								bandWidthToString(bw_msr), delta));

						// Acceptable measurement
						if (delta > min_possible_delta * (train_len - 1)) {
							measurs_P1[trains_msrd++] = bw_msr;
						}
						else {
							bad_tstamps++;
							sink.info(" (ignored)");
						}

						// # of trains received in this packet size iteration
						trains_per_size++;
					}
				} while (trains_per_size < no_trains_per_size);

				/* Tell sender to continue with next phase */
				pack_sz += pack_incr_step;
				if (pack_sz > max_pack_sz) pack_sz = max_pack_sz;
				/***** dealing with ignored measurements *****/
				if (bad_tstamps > no_trains_per_size / IGNORE_LIM_FACTOR) {
					/* If this is greater than max_train_len? :Ravi */
					train_len += 1;
					if (train_len > Math.max(max_train_len / 4, 2)) {
						abort_phase1 = true;
					}
					pack_sz += 275;
					if (pack_sz > max_pack_sz) pack_sz = max_pack_sz;
					if (!abort_phase1) {
						sink.info(String.format(
								"\tToo many ignored measurements..\n\tAdjust train length: %d packets\n\tAdjust packet size: %d bytes\n\n",
								train_len, Math.min(pack_sz, max_pack_sz) + 28));
					}
					else
						break;
				}
			}
		}

		/* Compute number of valid (non-ignored) measurements */
		no_trains = trains_msrd - 1;

		/*
		 * Detect all local modes in Phase I
		 */
		if (!abort_phase1) {
			/* Order measurements */
			System.arraycopy(measurs_P1, 0, ord_measurs_P1, 0, no_trains);
			Arrays.sort(ord_measurs_P1, 0, no_trains);

			/* Detect and store all local modes */
			sink.info("-- Local modes : In Phase I --");

			/* Mark all measurements as valid (needed for local mode detection) */
			for (int i = 0; i < no_trains; i++)
				measurs_vld_P1[i] = true;
			no_modes_P1 = 0;
			while ((curr_mode = MathHelper.extractMode(ord_measurs_P1, measurs_vld_P1, bin_wd)) != null) {
				/*
				 * the modes are ordered based on the number of measurements in
				 * the modal bin (strongest mode first)
				 */
				modes_P1[no_modes_P1++] = curr_mode;
				/*
				 * modes_P1[no_modes_P1].mode_value = mode_value;
				 * modes_P1[no_modes_P1].mode_cnt = mode_cnt;
				 * modes_P1[no_modes_P1].bell_cnt = bell_cnt;
				 * modes_P1[no_modes_P1].bell_lo = bell_lo;
				 * modes_P1[no_modes_P1].bell_hi = bell_hi;
				 * modes_P1[no_modes_P1].bell_kurtosis = bell_kurtosis;
				 * no_modes_P1++;
				 */
				if (no_modes_P1 >= MAX_NUMBER_OF_MODES) {
					sink.info("Increase MAX_NO_MODES constant");
					termint(writer, -1);
				}
			}
		}
		else {
			sink.info("\n\tAborting Phase I measurements..\n\tToo many ignored measurements\n\tPhase II will report lower bound on path capacity.");
		}
		int no_trains_P1 = no_trains;
		/* Tell sender to continue with next phase */
		// ctr_code = CONTINUE;
		// send_ctr_msg(ctr_code);

		Thread.sleep(1000);

		/*
		 * Phase II: Packet trains with maximum train length
		 */
		sink.info("-- Phase II: Estimate Asymptotic Dispersion Rate (ADR) --");

		/* Train spacing in Phase II */
		train_spacing = max_train_spacing;
		send_ctr_msg(writer, Command.TRAIN_SPACING, train_spacing);

		/* Determine train length for Phase II. Tell sender about it. */
		/*
		 * Note: the train length in Phase II is normally the maximum train
		 * length, determined in the "Maximum Train Length Discovery" phase
		 * which was executed earlier. The following constraint is only used in
		 * very low capacity paths.
		 */
		{
			int train_len = max_train_len;
			// train_len = (long) (((avg_bw*0.5) * (max_train_spacing*1000)) /
			// (max_pack_sz*8));
			// if (train_len > max_train_len) train_len = max_train_len;
			// if (train_len < train_len_P1) train_len = train_len_P1;
			send_ctr_msg(writer, Command.TRAIN_LEN, train_len);

			/* Packet size in Phase II. Tell sender about it. */
			pack_sz = max_pack_sz;
			send_ctr_msg(writer, Command.PCK_LEN, pack_sz);

			/*
			 * Number of trains in Phase II. Tell sender about it. Ravi:Not
			 * anymore..
			 */
			no_trains = NUMBER_OF_TRAINS_P2;

			sink.info(String.format("\t-- Number of trains: %d - Train length: %d - Packet size: %dB\n", no_trains,
					train_len, pack_sz + 28));

			/* Tell sender to start sending */
			round++;
			trains_msrd = 0;
			trains_rcvd = 0;
			bad_tstamps = 0;

			do {
				/* Wait for a complete packet train */
				if (!retransmit) {
					(round)++;
				}
				int bad_train = recv_train(train_len, round, time_stmps);

				// Compute dispersion and bandwidth measurement
				if (bad_train == 0) {
					double delta = time_stmps[train_len] - time_stmps[1];
					double bw_msr = ((28 + pack_sz) << 3) * (train_len - 1) / delta;
					sink.info(String.format("\tMeasurement-%4d out of %3d: %s (%.0f µsec)", trains_rcvd + 1, no_trains,
							bandWidthToString(bw_msr), delta));

					/* Acceptable measurement */
					if (delta > min_possible_delta * (train_len - 1)) {
						/* store bw value */
						measurs_P2[trains_msrd++] = bw_msr;
					}
					else {
						bad_tstamps++;
						sink.info(" (ignored)");
					}

					trains_rcvd++;
				}
			} while (trains_rcvd < no_trains);
		}

		/* Tell sender to continue with next phase */
		// ctr_code = CONTINUE;
		// send_ctr_msg(ctr_code);

		/* Compute number of valid (non-ignored measurements) */
		no_trains = trains_msrd - 1;

		/*
		 * --------------------------------------------------------- Detect all
		 * local modes in Phase II
		 * ---------------------------------------------------------
		 */

		/* Order measurements */
		System.arraycopy(measurs_P2, 0, ord_measurs_P2, 0, no_trains);
		Arrays.sort(ord_measurs_P2, 0, no_trains);

		/* Detect and store all local modes in Phase II */
		sink.info("-- Local modes : In Phase II --");

		/* Mark all measurements as valid (needed for local mode detection) */
		for (int train_no = 0; train_no < no_trains; train_no++)
			measurs_vld_P2[train_no] = true;
		no_modes_P2 = 0;
		while ((curr_mode = MathHelper.extractMode(ord_measurs_P2, measurs_vld_P2, bin_wd)) != null) {
			/*
			 * the modes are ordered based on the number of measurements in the
			 * modal bin (strongest mode first)
			 */
			modes_P2[no_modes_P2++] = curr_mode;
			/*
			 * modes_P2[no_modes_P2].mode_value = mode_value;
			 * modes_P2[no_modes_P2].mode_cnt = mode_cnt;
			 * modes_P2[no_modes_P2].bell_cnt = bell_cnt;
			 * modes_P2[no_modes_P2].bell_lo = bell_lo;
			 * modes_P2[no_modes_P2].bell_hi = bell_hi;
			 * modes_P2[no_modes_P2].bell_kurtosis = bell_kurtosis;
			 * no_modes_P2++;
			 */
			if (no_modes_P2 >= MAX_NUMBER_OF_MODES) {
				sink.error("Increase MAX_NO_MODES constant");
				termint(writer, -1);
			}
		}

		/*
		 * If the Phase II measurements are distributed in a very narrow fashion
		 * (i.e., low coefficient of variation, and std_dev less than bin
		 * width), and the ADR is not much lower than the earlier avg bandwidth
		 * estimate, the ADR is a good estimate of the capacity mode. This
		 * happens when the narrow link is of significantly lower capacity than
		 * the rest of the links, and it is only lightly used.
		 */
		/* Compute ADR estimate */
		double adr = MathHelper.getAverage(ord_measurs_P2, 10, no_trains - 20);
		std_dev = MathHelper.getStandardDeviation(ord_measurs_P2, 10, trains_msrd - 20);
		if (no_modes_P2 == 1 && std_dev / adr < COEF_VAR_THR && adr / avg_bw > ADR_REDCT_THR) {
			// sprintf(message,"    The capacity estimate will be based on the ADR mode.\n");
			// prntmsg(pathrate_fp);
			// if (Verbose) prntmsg(stdout);
			adr = (modes_P2[0].modeLowerValue + modes_P2[0].modeUpperValue) / 2.0;
		}
		if (no_modes_P2 > 1) {
			sink.warning("WARNING: Phase II did not lead to unimodal distribution.");
			sink.warning("The ADR estimate may be wrong. Run again later.");
			double max_merit = 0.;
			for (int i = 0; i < no_modes_P2; i++) {
				sink.info(String.format("\t %s to %s", bandWidthToString(modes_P2[i].modeLowerValue),
						bandWidthToString(modes_P2[i].modeUpperValue)));
				// merit = (modes_P2[i].mode_cnt / (double)modes_P2[i].bell_cnt)
				// Weiling: merit is calculated using kurtosis as the narrowness
				// of the bell
				double merit = modes_P2[i].bellKurtosis * (modes_P2[i].modeCount / (double) no_trains);
				sink.info(String.format(" - Figure of merit: %.2f\n", merit));
				if (merit > max_merit) {
					max_merit = merit;
					cap_mode_ind = i;
				}
			}
			adr = (modes_P2[cap_mode_ind].modeLowerValue + modes_P2[cap_mode_ind].modeLowerValue) / 2.0;
		}
		sink.info(String.format("--> Asymptotic Dispersion Rate (ADR) estimate: %s.", bandWidthToString(adr)));

		/*
		 * --------------------------------------------------------- The end of
		 * the story... Final capacity estimate
		 * ---------------------------------------------------------
		 */

		/*
		 * Final capacity estimate: the Phase I mode that is larger than ADR,
		 * and it has the maximum "figure of merit". This figure of metric is
		 * the "density fraction" of the mode, times the "strength fraction" of
		 * the mode. The "density fraction" is the number of measurements in the
		 * central bin of the mode, divided by the number of measurements in the
		 * entire bell of that mode. The "strength fraction" is the number of
		 * measurements in the central bin of the mode, divided by the number of
		 * measurements in the entire Phase I phase.
		 */
		if (!abort_phase1) {
			sink.info(String.format("--> Possible capacity values:"));
			double max_merit = 0.;
			for (int i = 0; i < no_modes_P1; i++) {
				/* Give possible capacity modes */
				if (modes_P1[i].modeUpperValue > adr) {
					sink.info(String.format("\t %s to %s", bandWidthToString(modes_P1[i].modeLowerValue),
							bandWidthToString(modes_P1[i].modeUpperValue)));
					// merit = (modes_P1[i].mode_cnt /
					// (double)modes_P1[i].bell_cnt)
					// Weiling: merit is calculated using kurtosis as the
					// narrowness of the bell
					double merit = modes_P1[i].bellKurtosis * (modes_P1[i].modeCount / (double) no_trains_P1);
					sink.info(String.format(" - Figure of merit: %.2f\n", merit));
					if (merit > max_merit) {
						max_merit = merit;
						cap_mode_ind = i;
					}
				}
			}

			// if (max_merit>0. && !adr_narrow)
			if (max_merit > 0.) {
				happy_end(senderAddress, modes_P1[cap_mode_ind].modeLowerValue, modes_P1[cap_mode_ind].modeUpperValue);
				termint(writer, 0);
			}
			/*
			 * If there is no Phase I mode that is larger than the ADR, //or if
			 * Phase II gave a very narrow distribution, the final capacity
			 * estimate is the ADR.
			 */
			else {
				/*
				 * If there are multiple modes in Phase II (not unique estimate
				 * for ADR), the best choice for the capacity mode is the
				 * largest mode of Phase II
				 */
				sink.info(String.format("\t %s to %s  - ADR mode\n", bandWidthToString(adr - bin_wd / 2.),
						bandWidthToString(adr + bin_wd / 2.)));

				happy_end(senderAddress, adr - bin_wd / 2., adr + bin_wd / 2.);
				termint(writer, 0);
			}
		}
		else {
			sink.info("--> Phase I was aborted.");
			sink.info("--> The following estimate is a lower bound for the path capacity.");
			happy_end(senderAddress, adr - bin_wd / 2., adr + bin_wd / 2.);
			termint(writer, 0);
		}
		System.exit(-1);
	}

	/**
	 * Calculates total per-packet processing time (transfer from NIC to kernel, processing at the kernel, and transfer
	 * at user space) of an UDP packet.
	 * 
	 * @param pack_buf
	 *            the buffer containing the payload of the UDP message to use
	 * @param pack_sz
	 *            the length of the payload
	 * @param no_pack_sizes
	 * @return the minimum delta that should be accepted as valid
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	private double calculateMinPossibleDelta(byte[] pack_buf, int pack_sz, int no_pack_sizes)
			throws UnknownHostException, IOException
	{
		double min_possible_delta;
		double[] min_OSdelta = new double[no_pack_sizes * 10];
		DatagramPacket packet = new DatagramPacket(pack_buf, pack_sz, InetAddress.getByName(null), udpReceiverPort);
		for (int j = 0; j < no_pack_sizes; j++) {
			for (int i = 0; i < 10; i++) {
				udpSocket.send(packet);
				long time = System.nanoTime();
				udpSocket.receive(packet);
				min_OSdelta[j * 10 + i] = System.nanoTime() - time;
			}
		}
		// Use median of measured latencies to avoid outliers
		Arrays.sort(min_OSdelta, 0, min_OSdelta.length);
		min_possible_delta = min_OSdelta[min_OSdelta.length / 2] / 1000; // microseconds
		return min_possible_delta;
	}

	/*
	 * Print a bandwidth measurement (given in Mbps) in the appropriate units
	 */
	static String bandWidthToString(double bandwidth)
	{
		if (bandwidth < 1.0) {
			return String.format(" %.0f kbps ", bandwidth * 1000);
		}
		else if (bandwidth < 15.0) {
			return String.format(" %.1f Mbps ", bandwidth);
		}
		else {
			return String.format(" %.0f Mbps ", bandwidth);
		}
	}

	/*
	 * Terminate measurements
	 */
	void termint(OutputStream writer, int exit_code) throws IOException
	{
		send_ctr_msg(writer, Command.CONTINUE, 0);
		send_ctr_msg(writer, Command.GAME_OVER, 0);
		// close(sock_tcp);
		// close(sock_udp);
		// exit(exit_code);
	}

	/* Successful termination. Print result. */
	void happy_end(InetAddress senderAddress, double bw_lo, double bw_hi)
	{
		sink.info(String.format("Final capacity estimate: %s to %s", bandWidthToString(bw_lo), bandWidthToString(bw_hi)));

		Date today = new Date();
		sink.info(" DATE = " + today);
		String localHostName = "";
		try {
			localHostName = java.net.InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException e) {
		}
		sink.info(" HOST = " + (localHostName.equals("") ? localHostName : "NO_NAME"));
		sink.info(" PROG = pathrate");
		sink.info(" LVL = Usage");
		sink.info(" PATHRATE.SNDR = " + senderAddress.getHostName());
		sink.info(String.format(" PATHRATE.CAPL = %.1fMbps\n", bw_lo));
		sink.info(String.format(" PATHRATE.CAPH = %.1fMbps\n", bw_hi));

		capacityData = new CapacityData();
		capacityData.capacityEstimateLower = bw_lo;
		capacityData.capacityEstimateUpper = bw_hi;
	}

	// Receive a complete packet train from the sender
	// time must be long at least train_len elements
	int recv_train(int train_len, int round, long[] time) throws IOException
	{
		int exp_pack_id = 0, bad_train = 0;
		boolean recv_train_done = false;

		send_ctr_msg(writer, Command.SEND, round);

		byte[] buffer = new byte[MAX_PACKET_SIZE];
		DatagramPacket packet = new DatagramPacket(buffer, MAX_PACKET_SIZE);
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);
		int waitTime = 1000;
		udpSocket.setSoTimeout(waitTime);
		do {
			// TODO: is there a way in Java to wait on multiple monitors?
			// Can we estimate the time a train should take to be received after the SEND command ?
			long timestamp;
			try {
				udpSocket.receive(packet);
				timestamp = System.nanoTime() / 1000;
			}
			catch (InterruptedIOException e) {
				break;
			}
			time[exp_pack_id] = timestamp;

			bb.position(0);
			int pack_id = bb.getInt(0);
			// int train_id = bb.getInt(4);
			int round_id = bb.getInt(8);

			if (round_id != round) {
				bad_train = 1;
			}
			else {
				if (pack_id == 0) {
					bad_train = 0;
					exp_pack_id = 1;
				}
				else if (pack_id == exp_pack_id) {// &&
													// train_id==exp_train_id)
					exp_pack_id++;
				}
			}
		} while (true);

		if (exp_pack_id == train_len + 1) {
			recv_train_done = true;
		}
		// reader.read(fbb, 0, 4);
		if (recv_train_done) {
			// Send control message to ACK burst
			send_ctr_msg(writer, Command.ACK_TRAIN, 0);
			return bad_train;
		}
		else {
			// Send signal to recv_train
			send_ctr_msg(writer, Command.NEG_ACK_TRAIN, 0);
			return 2;
		}
	}

	/*
	 * Trying long trains to detect capacity in case interrupt coalescing
	 * detected.
	 */
	int gig_path(InetAddress senderAddress, OutputStream writer, boolean retransmit, int pack_sz, int round,
			int k_to_u_latency) throws IOException
	{
		int est = 0, tmp;
		int train_len = 200, bad_train, no_of_trains = 10;
		long[] pkt_time = new long[train_len + 1];
		double[] cap = new double[300], ord_cap = new double[300];

		sink.info("Test with Interrupt Coalesence");
		sink.info(String.format("%d trains of length: %d \n", no_of_trains, train_len));
		for (int j = 0; j < no_of_trains; j++) {
			int[] disps = new int[300];
			int num_b2b = 1;
			int k = 0;
			int[] id = new int[300];
			double tmp_cap, adr;

			sink.info(String.format("  Train id: %d ->", j));

			send_ctr_msg(writer, Command.TRAIN_LEN, train_len);
			send_ctr_msg(writer, Command.PCK_LEN, pack_sz);

			bad_train = recv_train(train_len, round, pkt_time);
			if (bad_train == 2) {// train too big... try smaller
				train_len -= 20;
				sink.info(String.format("Reducing train size to %d", train_len));
				if (train_len < 100 && est < 5) {
					sink.info("Insufficient number of packet dispersion estimates.");
					sink.info("Probably a 1000Mbps path.");
					termint(writer, -1);
				}
			}
			else {
				adr = (train_len - 2) * 12000 / (pkt_time[train_len] - pkt_time[1]);
				for (int i = 2; i <= train_len; i++) {
					disps[i] = (int) (pkt_time[i] - pkt_time[i - 1]);
					// fprintf(stderr,"%d %d, ",i, disps[i]);
				}
				id[k++] = 1;
				num_b2b = 1;
				for (int i = 2; i < train_len; i++) {
					if ((num_b2b <= 5) || (disps[i] < num_b2b * 1.5 * k_to_u_latency)) {
						num_b2b++;
					}
					else {
						id[k++] = i;
						num_b2b = 1;
					}
				}
				for (int i = 1; i < k - 1; i++) {
					tmp_cap = 12000.0 * (id[i + 1] - id[i] - 1) / (pkt_time[id[i + 1]] - pkt_time[id[i]]);
					if (tmp_cap >= .9 * adr) {
						cap[est] = tmp_cap;
						sink.info(String.format(" %s \n\too", bandWidthToString(cap[est++])));
					}
				}
				sink.info(String.format("Number of jump detected = %d", k));
			}
		}
		if (est > 1) {
			System.arraycopy(cap, 0, ord_cap, 0, est);
			Arrays.sort(ord_cap, 0, est);
			tmp = (int) (.9 * est);
			happy_end(senderAddress, ord_cap[tmp - 1], ord_cap[tmp]);
		}
		else {
			sink.info(String.format("Insufficient number %d of packet dispersion estimates.", est));
			sink.info("Probably a 1000Mbps path.");
			termint(writer, -1);
		}

		termint(writer, 0);
		return (1);
	}

	private boolean intr_coalescence(long[] time, int len, double latency) throws IOException
	{
		// TODO: il vettore di delta è completamente inutile
		double[] delta = new double[time.length];
		int b2b = 0;
		for (int i = 2; i < len; i++) {
			delta[i] = time[i] - time[i - 1];
			sink.debug(String.format("DEBUG: i %d, disp[i] %.2f\n", i, delta[i]));
			if (delta[i] <= 2.5 * latency) {
				b2b++;
			}
		}
		sink.debug(String.format("DEBUG: b2b %d len %d\n", b2b, len));
		return (b2b > .6 * len);
	}

	byte[] fbb = new byte[4];

	private void send_ctr_msg(OutputStream writer, Command command, int data) throws IOException
	{
		int i = command.ordinal() | (data << 8);
		ByteBuffer.wrap(fbb).order(ByteOrder.BIG_ENDIAN).putInt(i);
		writer.write(fbb);
		sink.debug(String.format("[%08X] %s(%d)", i, command, data));
	}

	@Override
	public CapacityData getCapacityData()
	{
		return capacityData;
	}
}
