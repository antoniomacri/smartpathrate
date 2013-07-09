package org.pathrate.core;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

public class SmartPathrate implements IPathrate
{
	protected static final int TCP_SENDER_PORT = 13000;
	protected static final int UDP_RECEIVER_PORT = 13000;
	protected static final int MAXIMUM_TRANSMISSION_UNIT = 1500;
	protected static final int IP_UDP_HEADER_SIZE = 20 + 8;
	protected static final int MAX_PAYLOAD_SIZE = MAXIMUM_TRANSMISSION_UNIT - IP_UDP_HEADER_SIZE;
	protected static final int UDP_BUFFER_SIZE = MAXIMUM_TRANSMISSION_UNIT * 200;
	protected static final int RTT_ATTEMPTS = 10;
	protected static final int MIN_TRAIN_SPACING = 150;
	protected static final int MIN_PROBE_TIMEOUT = 150;

	protected static final int MAX_ROUND_COUNT = 15;
	protected static final int MAX_BAD_TRAINS = 5;
	protected static final int ROUND_SIZE = 10;
	protected static final int MINIMUM_TRAIN_LENGTH = 40;

	protected static final int MIN_REQUIRED_PAIR_CAPACITIES = 1000;

	/**
	 * Represents a command used for interaction between the sender and the receiver.
	 */
	protected enum Command {
		/**
		 * The size of the UDP payload.
		 */
		PAYLOAD_SIZE,
		/**
		 * The length of the train specified in number of packets.
		 */
		TRAIN_LENGTH, SEND, TRAIN_SENT, ACK_TRAIN, NEG_ACK_TRAIN, GAME_OVER, ERROR
	}

	public CapacityData capacityData;

	// A temporary big-endian buffer used locally by many methods
	ByteBuffer fourByteBuffer = ByteBuffer.wrap(new byte[4]).order(ByteOrder.BIG_ENDIAN);

	protected ISink sink;
	private IConnectionSpeedProvider connectionSpeedProvider;
	private InputStream tcpReader;
	private OutputStream tcpWriter;
	private DatagramSocket udpSocket;

	// Parameters calculated at run-time
	protected int trainSpacing;
	protected int probeTimeout;
	protected int kernelToUserLatency;
	protected int minPossibleDelta;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.pathrate.core.IPathrate#install(org.pathrate.core.ISink,
	 * org.pathrate.core.ICancelTask)
	 */
	public void install(ISink sink, IConnectionSpeedProvider connectionSpeedProvider)
	{
		this.sink = sink;
		this.connectionSpeedProvider = connectionSpeedProvider;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.pathrate.core.IPathrate#startAsSender()
	 */
	public void startAsSender(ICancelTask task) throws IOException
	{
		sink.info("Starting as sender (port " + TCP_SENDER_PORT + ")...");
		ServerSocket listener = new ServerSocket(TCP_SENDER_PORT);

		udpSocket = new DatagramSocket();
		udpSocket.setSendBufferSize(UDP_BUFFER_SIZE);
		sink.info("Sender UDP buffer size: " + udpSocket.getSendBufferSize() + " bytes.");

		interactive: do {
			sink.info("Waiting for receiver to establish control stream...");
			Socket tcpSocket = listener.accept();
			sink.info("Connected to " + tcpSocket.getInetAddress().getHostAddress() + ":" + tcpSocket.getPort() + ".");

			try {
				tcpReader = tcpSocket.getInputStream();
				tcpWriter = tcpSocket.getOutputStream();

				// Form receiving UDP address
				InetAddress udpRemoteAddress = tcpSocket.getInetAddress();

				int payloadSize = MAX_PAYLOAD_SIZE;
				int trainLength = 3;
				int trainId = 1;

				Command commandCode = Command.ERROR;
				int commandData;

				sink.info("Measuring Roud-Trip Time...");
				estimateRoundTripTimeSender(tcpReader, tcpWriter);

				// Create random packet payload to deal with links that do payload
				// compression
				byte[] packetBuffer = new byte[MAX_PAYLOAD_SIZE];
				Random random = new Random();
				random.nextBytes(packetBuffer);

				boolean done = false;

				while (!done) {
					sink.info("Waiting for commands...");
					do {
						if (task.isCancelled()) {
							done = true;
							sink.info("Cancelled.");
							break interactive;
						}
						if (tcpReader.read(fourByteBuffer.array(), 0, 4) < 4) {
							done = true;
							break;
						}
						// Get the command and the data fields from the control
						// message
						commandData = fourByteBuffer.getInt(0) >> 8;
						if (fourByteBuffer.get(3) >= Command.values().length) {
							commandCode = Command.ERROR;
						}
						else {
							commandCode = Command.values()[fourByteBuffer.get(3)];
						}

						sink.info(String.format("[%08X] %s(%d)", fourByteBuffer.getInt(0), commandCode, commandData));

						switch (commandCode) {
						case PAYLOAD_SIZE:
							payloadSize = commandData;
							break;
						case TRAIN_LENGTH:
							trainLength = commandData;
							break;
						case SEND:
							trainId = commandData;
							break;
						case ACK_TRAIN:
						case NEG_ACK_TRAIN:
							// An ACK or NEG_ACK for a packet train
							break;
						case GAME_OVER:
							// End of measurements
							done = true;
							break;

						default:
							sink.info("Unexpected control message... aborting.");
							done = true;
						}
					} while (commandCode != Command.SEND && !done);

					if (!done && commandCode == Command.SEND) {
						DatagramPacket packet = new DatagramPacket(packetBuffer, 0, payloadSize, udpRemoteAddress,
								UDP_RECEIVER_PORT);
						// Each packet carries a packet id (unique in each train)
						// and a round id (unique in the entire
						// execution).
						ByteBuffer buffer = ByteBuffer.wrap(packetBuffer).order(ByteOrder.BIG_ENDIAN);
						buffer.putInt(0);
						buffer.putInt(trainId);
						for (int packetId = 0; packetId < trainLength; packetId++) {
							buffer.position(0);
							buffer.putInt(packetId);
							udpSocket.send(packet);
						}
					}
				}

				tcpSocket.close();
			}
			catch (Throwable e) {
				StringWriter writer = new StringWriter();
				PrintWriter printWriter = new PrintWriter(writer);
				e.printStackTrace(printWriter);
				printWriter.flush();
				sink.error(writer.toString());
			}
		} while (true);

		listener.close();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.pathrate.core.IPathrate#startAsReceiver(java.net.InetAddress)
	 */
	public void startAsReceiver(InetAddress senderAddress, ICancelTask task) throws IOException, InterruptedException
	{
		sink.info("Starting as receiver...");

		sink.info("Creating UDP socket...");
		udpSocket = new DatagramSocket(null); // null as SocketAddress, to
												// create an unbound socket
		udpSocket.setReuseAddress(true);
		udpSocket.setReceiveBufferSize(UDP_BUFFER_SIZE);
		udpSocket.bind(new InetSocketAddress(UDP_RECEIVER_PORT));

		sink.info("Creating TCP connection (" + senderAddress.getHostAddress() + ":" + TCP_SENDER_PORT + ")...");
		Socket tcpSocket = new Socket();
		try {
			tcpSocket.connect(new InetSocketAddress(senderAddress, TCP_SENDER_PORT), 0);
		}
		catch (IOException e) {
			sink.error("Cannot connect. Make sure that SmartPathrate runs at sender.");
			tcpSocket.close();
			return;
		}

		tcpReader = tcpSocket.getInputStream();
		tcpWriter = tcpSocket.getOutputStream();

		final int payloadSize = MAX_PAYLOAD_SIZE, headersSize = IP_UDP_HEADER_SIZE;

		sink.info(String.format("Estimating kernel-to-user latency for a UDP packet of %d bytes...", payloadSize));
		kernelToUserLatency = calculateKernelToUserLatency(udpSocket, payloadSize);
		sink.info(String.format("Kernel-to-user latency: %d µs.", kernelToUserLatency));

		sink.info("Measuring Round-Trip Time...");
		final int roundTripTime = estimateRoundTripTimeReceiver(tcpReader, tcpWriter);
		sink.info(String.format("Round-Trip Time: %d ms.", roundTripTime));

		final double trainSpacingMultiplier = 1.25;
		trainSpacing = Math.max(MIN_TRAIN_SPACING, (int) (roundTripTime * trainSpacingMultiplier));
		sink.info(String.format("Train spacing: %d ms.", trainSpacing));

		probeTimeout = Math.max(MIN_PROBE_TIMEOUT, 3 * roundTripTime);
		sink.info(String.format("Probe timeout: %d ms.", probeTimeout));

		// Keep a unique identifier for each train (train/measurement) in
		// pathrate's execution.
		int trainId = 0;

		sendCommand(Command.PAYLOAD_SIZE, payloadSize);

		// Store a few parameters
		long timerResolution = 0;
		for (long first = System.nanoTime(); timerResolution == 0; timerResolution = System.nanoTime() - first) {
		}
		Stats.writeParam("timerresolution", timerResolution);
		Stats.writeParam("kerneltouserlatency", kernelToUserLatency);

		CapacityData data = new CapacityData();
		int totalBadTrains = 0, numberOfRounds = 0;
		int maxCumulativeDispersion = 0, maxTrainLength = 0;
		long startTime = System.nanoTime(), runningTime = startTime;
		try {
			for (int trainLength = MINIMUM_TRAIN_LENGTH, round = 0; round < MAX_ROUND_COUNT; round++) {
				sendCommand(Command.TRAIN_LENGTH, trainLength);
				sink.info("Train length: " + trainLength + " packets.");

				final int wifiSpeedMbps = (int) connectionSpeedProvider.detectWifiSpeed();
				sink.info(String.format("Wi-Fi connection speed: %d Mbps.", wifiSpeedMbps));

				minPossibleDelta = (headersSize + payloadSize) * 8 / wifiSpeedMbps;
				sink.info(String.format("Minimum acceptable packet pair dispersion: %d µs.", minPossibleDelta));

				int[][] timestamps = new int[ROUND_SIZE][trainLength];

				int step = 0, nextTrainLength = (int) (1.25 * trainLength);
				for (; step < ROUND_SIZE; step++) {
					int badTrains = 0;
					while (badTrains < MAX_BAD_TRAINS) {
						Thread.sleep(trainSpacing);
						int result = receiveTrain(payloadSize, trainLength, trainId, timestamps[step]);
						trainId++; // Always increase train ID
						if (result == trainLength) {
							break;
						}
						badTrains++;
					}
					totalBadTrains += badTrains;
					if (badTrains >= MAX_BAD_TRAINS) {
						nextTrainLength = trainLength - (int) (0.05 * trainLength);
						if (nextTrainLength < MINIMUM_TRAIN_LENGTH && round > 0) {
							sink.error("Cannot successfully receive packet trains.");
							sink.error("Aborting.");
							sendCommand(Command.GAME_OVER, 0);
							tcpSocket.close();
							return;
						}
						break;
					}

					maxTrainLength = Math.max(maxTrainLength, trainLength);
					int cumdisp = timestamps[step][trainLength - 1] - timestamps[step][0];
					maxCumulativeDispersion = Math.max(maxCumulativeDispersion, cumdisp);
					Stats.saveTimestamps(timestamps[step]);

					Stats.writeParam("wifispeed@%d", wifiSpeedMbps);
					Stats.writeParam("trainlength@%d", trainLength);
					Stats.writeParam("minpossibledelta@%d", minPossibleDelta);
				}
				numberOfRounds++;
				estimateCapacity(data, timestamps, step, trainLength, payloadSize + headersSize);

				Stats.writeParam("numberofrounds@%d", numberOfRounds);
				Stats.writeParam("numberoftrains@%d", data.numberOfTrains);
				Stats.writeParam("badtrains@%d", totalBadTrains);
				Stats.writeParam("maxtrainlength@%d", maxTrainLength);
				Stats.writeParam("binwidth@%d", data.binWidth);

				if (data.prevCapacityEstimateUpper != 0) {
					double middleCapacity = (data.capacityEstimateLower + data.capacityEstimateUpper) / 2;
					if (middleCapacity >= data.prevCapacityEstimateLower
							&& middleCapacity <= data.prevCapacityEstimateUpper) {
						if (data.canStop == 3) {
							// break;
						}
						data.canStop++;
					}
				}

				Stats.writeResult("canstop@%d", data.canStop);
				Stats.writeResult("runningtime@%d", (System.nanoTime() - startTime) / (1000 * 1000 * 1000));
				Stats.writeResult("totaldatasent@%d", data.totalBytesSent);
				Stats.writeResult("numberofcapacityestimates@%d", data.pairCapacities.length);
				Stats.writeResult("capacityresolution@%d", "%.2f", data.binWidth);
				Stats.writeResult("adr@%d", "%.1f", data.adrValue);
				Stats.writeResult("finalcapacityestimatelower@%d", "%.2f", data.capacityEstimateLower);
				Stats.writeResult("finalcapacityestimateupper@%d", "%.2f", data.capacityEstimateUpper);
				Stats.writeResult("status@%d", data.status);
				if (data.done) {
					break;
				}
				trainLength = nextTrainLength;
			}
			runningTime = (System.nanoTime() - startTime) / (1000 * 1000 * 1000);

			sendCommand(Command.GAME_OVER, 0);
		}
		finally {
			tcpSocket.close();
		}

		// Save execution's parameters
		Stats.writeParam("numberofrounds", numberOfRounds);
		Stats.writeParam("numberoftrains", data.numberOfTrains);
		Stats.writeParam("badtrains", totalBadTrains);
		// Only useful for pgf plots
		Stats.writeParam("maxtrainlength", maxTrainLength);
		Stats.writeParam("maxcumulativedispersion", maxCumulativeDispersion);

		// Save results
		Stats.writeResult("runningtime", runningTime);
		Stats.writeResult("totaldatasent", data.totalBytesSent);
		Stats.writeResult("numberofcapacityestimates", data.pairCapacities.length);
		Stats.writeResult("capacityresolution", "%.2f", data.binWidth);
		Stats.writeResult("adr", "%.1f", data.adrValue);
		Stats.writeResult("finalcapacityestimatelower", "%.2f", data.capacityEstimateLower);
		Stats.writeResult("finalcapacityestimateupper", "%.2f", data.capacityEstimateUpper);
		Stats.writeResult("status", data.status);

		capacityData = data;
	}

	/**
	 * Calculates an approximated value for the kernel-to-user latency of a UDP packet (i.e. the total per-packet
	 * processing time (transfer from NIC to kernel, processing at the kernel, and transfer at user space).
	 * 
	 * @param payloadSize
	 *            the size of the UDP payload
	 * @throws UnknownHostException
	 * @throws IOException
	 * @return an approximated value for the kernel-to-user latency
	 */
	private static int calculateKernelToUserLatency(DatagramSocket udpSocket, int payloadSize)
			throws UnknownHostException, IOException
	{
		// Create random payload (maybe here it doesn't matter)
		byte[] packetBuffer = new byte[MAX_PAYLOAD_SIZE];
		Random random = new Random();
		random.nextBytes(packetBuffer);
		DatagramPacket packet = new DatagramPacket(packetBuffer, payloadSize, InetAddress.getByName(null),
				UDP_RECEIVER_PORT);

		int attempts = 400;
		long[] kernelToUserLatencies = new long[attempts];
		for (int i = 0; i < attempts; i++) {
			udpSocket.send(packet);
			long time = System.nanoTime();
			udpSocket.receive(packet);
			kernelToUserLatencies[i] = (System.nanoTime() - time) / 1000; // microseconds
		}
		Arrays.sort(kernelToUserLatencies, 0, attempts);
		return (int) kernelToUserLatencies[(int) (0.9 * attempts)];
	}

	/**
	 * Allows the receiver to measure the round-trim time, echoing received packets.
	 * 
	 * @param reader
	 *            the input stream associated to the UDP socket
	 * @param writer
	 *            the output stream associated to the UDP socket
	 * @throws IOException
	 */
	private void estimateRoundTripTimeSender(InputStream reader, OutputStream writer) throws IOException
	{
		for (int i = 0; i < RTT_ATTEMPTS; i++) {
			reader.read(fourByteBuffer.array());
			writer.write(fourByteBuffer.array());
			writer.flush();
		}
	}

	/**
	 * Estimates the round-trip time transmitting a few packets to the sender and waiting for the replies.
	 * 
	 * @param reader
	 *            the input stream associated to the UDP socket
	 * @param writer
	 *            the output stream associated to the UDP socket
	 * @return the estimated round-trip time in milliseconds
	 * @throws IOException
	 */
	private int estimateRoundTripTimeReceiver(InputStream reader, OutputStream writer) throws IOException
	{
		fourByteBuffer.putInt(0, (int) System.nanoTime()); // put a random value

		long sumRtt = 0;
		for (int i = 0; i < RTT_ATTEMPTS; i++) {
			long time = System.nanoTime();
			writer.write(fourByteBuffer.array());
			writer.flush();
			reader.read(fourByteBuffer.array());
			// ignore first rtt
			if (i > 0) {
				sumRtt += (System.nanoTime() - time) / 1000;
			}
		}
		return (int) ((sumRtt / RTT_ATTEMPTS) / 1000);
	}

	/**
	 * Sends a command and associated data through the TCP socket.
	 * 
	 * @param command
	 *            the command to be sent
	 * @param data
	 *            the data associated with the specified command
	 * @throws IOException
	 */
	private void sendCommand(Command command, int data) throws IOException
	{
		int commandAndData = command.ordinal() | (data << 8);
		fourByteBuffer.putInt(0, commandAndData);
		tcpWriter.write(fourByteBuffer.array());
		sink.debug(String.format("[%08X] %s(%d)", commandAndData, command, data));
	}

	/**
	 * Receive a complete packet train from the sender. If a packet is not received, its timestamp is set to 0.
	 * 
	 * @param payloadSize
	 *            the size of the payload of each UDP packet
	 * @param trainLength
	 *            the number of packets to receive
	 * @param trainId
	 *            a unique number identifying the current train
	 * @param timestamps
	 *            an array that will contain the measured timestamps. It must be long at least trainLength elements
	 * @return an integer specifying the number of packets received correctly (i.e., in order)
	 * @throws IOException
	 */
	private int receiveTrain(int payloadSize, int trainLength, int trainId, int[] timestamps) throws IOException
	{
		byte[] buffer = new byte[MAX_PAYLOAD_SIZE];
		DatagramPacket packet = new DatagramPacket(buffer, payloadSize);
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);

		// Remove packets from buffer
		udpSocket.setSoTimeout(10);
		while (true) {
			try {
				udpSocket.receive(packet);
				Stats.writeParam("oldPackets@" + trainId, true);
			}
			catch (InterruptedIOException e) {
				break;
			}
		}

		Arrays.fill(timestamps, 0, trainLength, 0);
		udpSocket.setSoTimeout(probeTimeout);
		sendCommand(Command.SEND, trainId);

		int expectedPacketId = 0;
		while (expectedPacketId < trainLength) {
			int timestamp;
			try {
				udpSocket.receive(packet);
				timestamp = (int) (System.nanoTime() / 1000);
			}
			catch (InterruptedIOException e) {
				sendCommand(Command.NEG_ACK_TRAIN, trainId);
				return expectedPacketId;
			}

			int rcvPacketId = bb.getInt(0);
			int rcvTrainId = bb.getInt(4);

			if (rcvTrainId == trainId && rcvPacketId == expectedPacketId) {
				timestamps[expectedPacketId] = timestamp;
				expectedPacketId++;
			}
			// If was received a packet from previous rounds, we just treat it
			// as cross traffic
			// (i.e., we ignore it)
		}
		sendCommand(Command.ACK_TRAIN, trainId);
		return expectedPacketId;
	}

	protected void estimateCapacity(CapacityData data, int[][] allTimestamps, int tcount, int trainLength,
			int packetSize) throws FileNotFoundException
	{
		for (int tt = 0; tt < tcount; tt++) {
			int[] timestamps = allTimestamps[tt];
			int[] deltas = calculateDeltas(timestamps, trainLength);
			int[][] jumps = calculateJumpsAndPlateaus(deltas, minPossibleDelta, kernelToUserLatency);

			Stats.writePlotData("deltas%d", deltas);
			Stats.writePlotData("deltadeltas%d", calculateDeltas(deltas, trainLength));
			Stats.writePerMeasurementJumps(timestamps, jumps);

			double[] pairCaps = calculateCapacitiesFromFilteredDeltas(deltas, jumps, packetSize);
			Arrays.sort(pairCaps);
			data.pairCapacities = ArrayHelper.mergeSortedArrays(data.pairCapacities, pairCaps);

			double[] adrCaps = calculateCapacityFromAdr(timestamps, jumps, trainLength, packetSize);
			Arrays.sort(adrCaps);
			data.adrCapacities = ArrayHelper.mergeSortedArrays(data.adrCapacities, adrCaps);
		}
		data.numberOfTrains += tcount;
		data.totalBytesSent += tcount * trainLength * packetSize;

		data.binWidth = MathHelper.calculateBinWidth(data.pairCapacities);
		data.capacityModes = MathHelper.calculateModes(data.pairCapacities, data.binWidth);

		data.adrBinWidth = MathHelper.calculateBinWidth(data.adrCapacities);
		data.adrModes = MathHelper.calculateModes(data.adrCapacities, data.adrBinWidth);

		Stats.writePlotData("paircaps@%d", MathHelper.calculateDistribution(data.pairCapacities, data.binWidth),
				data.binWidth);
		Stats.writePlotData("adrcaps@%d", MathHelper.calculateDistribution(data.adrCapacities, data.adrBinWidth),
				data.adrBinWidth);

		Stats.writeModes("paircaps@%d", data.capacityModes);
		Stats.writeModes("adrcaps@%d", data.adrModes);

		if (data.pairCapacities.length == 0) {
			data.status = "No pair capacities.";
			return;
		}
		if (data.adrCapacities.length == 0) {
			data.status = "No ADR capacities.";
			return;
		}

		// Wait to gather at least a certain number of capacities
		if (data.pairCapacities.length < MIN_REQUIRED_PAIR_CAPACITIES) {
			data.status = "No enough pair capacities (min required: " + MIN_REQUIRED_PAIR_CAPACITIES + ").";
			return;
		}

		if (data.adrModes.length == 0) {
			// It happens sometimes
			data.status = "No ADR modes.";
			return;
		}

		// We can now calculate ADR...
		double maxMerit = 0.;
		MathHelper.Mode mode = data.adrModes[0];
		for (int i = 1; i < data.adrModes.length; i++) {
			MathHelper.Mode m = data.adrModes[i];
			double merit = m.bellKurtosis * (m.modeCount / (double) m.totalCount);
			if (merit > maxMerit) {
				maxMerit = merit;
				mode = m;
			}
		}
		data.adrValue = (mode.modeLowerValue + mode.modeUpperValue) / 2.0;

		// ...and estimate capacity

		// Peak the mode that is larger than ADR and has the maximum
		// "figure of merit".
		// This figure of merit is the "density fraction" of the mode, times the
		// "strength fraction" of
		// the mode. The "density fraction" is the number of measurements in the
		// central bin of the mode,
		// divided by the number of measurements in the entire bell of that
		// mode. The "strength fraction"
		// is the number of measurements in the central bin of the mode, divided
		// by the total number of
		// measurements.

		maxMerit = 0.0;
		mode = null;
		for (MathHelper.Mode m : data.capacityModes) {
			if (m.modeUpperValue >= 0.95 * data.adrValue) {
				double merit = m.bellKurtosis * (m.modeCount / (double) m.totalCount);
				if (merit > maxMerit) {
					maxMerit = merit;
					mode = m;
				}
			}
		}

		if (mode == null) {
			data.capacityEstimateLower = data.adrValue - data.binWidth / 2.;
			data.capacityEstimateUpper = data.adrValue + data.binWidth / 2.;
			data.status = "Capacity estimation from ADR.";
		}
		else {
			data.capacityEstimateLower = mode.modeLowerValue;
			data.capacityEstimateUpper = mode.modeUpperValue;
			data.status = "Capacity estimation from pair capacities.";
		}

		data.done = false;
	}

	protected static int[] calculateDeltas(int[] timestamps, int trainLength)
	{
		int[] deltas = new int[trainLength];
		for (int i = trainLength - 1; i > 0; i--) {
			deltas[i] = timestamps[i] - timestamps[i - 1];
		}
		deltas[0] = 0;
		return deltas;
	}

	/**
	 * @param deltas
	 *            an array containing the measured dispersions between consecutive packets
	 * @param minPossibleDelta
	 *            a value (in microseconds) specifying the minimum possible delta in according to the current
	 *            interface's speed
	 * @param kernelToUserLatency
	 *            an approximated value of the kernel-to-user latency
	 * @return
	 */
	protected int[][] calculateJumpsAndPlateaus(int[] deltas, int minPossibleDelta, int kernelToUserLatency)
	{
		int count = 0;
		int[] jumps = new int[deltas.length];
		int[] plateaus = new int[deltas.length];
		int prevIndex = deltas.length - 1; // the last packet of a plateau
		int outOfProfile = 0;
		for (int i = deltas.length - 2; i >= 0; i--) {
			// System.out.format("delta[%d] == %d:  -->  ", i, deltas[i]);
			if (i == 0) {
			}
			else if (deltas[i] <= minPossibleDelta) {
				// System.out.println("continue because not greater than minPossibleDelta");
				continue;
			}
			else if (deltas[i] <= 5 * kernelToUserLatency) {
				double maxktu = 1.5;
				if (deltas[i] - deltas[i - 1] <= maxktu * kernelToUserLatency) {
					// System.out.println("continue");
					continue;
				}
				else if (deltas[i] - deltas[i - 1] <= (maxktu + (1.0 / ++outOfProfile)) * kernelToUserLatency) {
					if (i > 1 && deltas[i - 1] - deltas[i - 2] <= maxktu * kernelToUserLatency) {
						// System.out.println("continue with overshoot");
						i--;
						continue;
					}
				}
			}
			if (prevIndex - i >= 8) {
				jumps[count] = i;
				plateaus[count] = prevIndex - i + 1;
				// System.out.format("set jump at packet %d with plateau %d",
				// jumps[count], plateaus[count]);
				count++;
				prevIndex = i - 1;
			}
			else {
				prevIndex = i - 1;
				// System.out.format("skip (less than 10 packets)");
			}
			outOfProfile = 0;
			// System.out.println(", restart from packet " + prevIndex);
		}
		int[][] result = new int[][] { new int[count], new int[count] };
		for (int i = 0; i < count; i++) {
			result[0][i] = jumps[count - 1 - i];
			result[1][i] = plateaus[count - 1 - i];
		}
		return result;
	}

	private static double[] calculateCapacitiesFromFilteredDeltas(int[] deltas, int[][] jumps, int packetSize)
	{
		double[] caps = new double[deltas.length - 1];
		int count = 0;
		int j = 1;
		for (int i = 0; i < jumps[0].length; i++) {
			for (; j < jumps[0][i]; j++) {
				caps[count++] = (packetSize * 8) / deltas[j];
			}
			j += jumps[1][i];
		}
		for (; j < deltas.length; j++) {
			caps[count++] = (packetSize * 8) / deltas[j];
		}
		double[] result = new double[count];
		System.arraycopy(caps, 0, result, 0, count);
		return result;
	}

	private static double[] calculateCapacityFromAdr(int[] timestamps, int[][] jumps, int trainLength, int packetSize)
	{
		if (jumps[0].length > 0) {
			// Skip if there is a jump at the beginning or at the end of the
			// measurement
			// (we consider the first two and the last two packets)
			int lastJump = jumps[0].length - 1;
			if (jumps[0][0] <= 1 || jumps[0][lastJump] + jumps[1][lastJump] >= trainLength - 1) {
				return new double[0];
			}
		}
		return new double[] { ((trainLength - 1) * packetSize * 8) / (timestamps[trainLength - 1] - timestamps[0]) };
	}

	@Override
	public CapacityData getCapacityData()
	{
		return capacityData;
	}
}
