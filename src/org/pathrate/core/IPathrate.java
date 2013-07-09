/**
 * @author Antonio Macrì, Francesco Racciatti, Silvia Volpe
 */
package org.pathrate.core;

import java.io.IOException;
import java.net.InetAddress;

public interface IPathrate
{
	/**
	 * Represents data used for capacity estimates.
	 */
	public class CapacityData
	{
		public String status = "";
		public boolean done;
		public double binWidth;
		public double adrBinWidth;
		public int numberOfTrains;
		public int totalBytesSent;
		public double[] pairCapacities = new double[0];
		public double[] adrCapacities = new double[0];
		public MathHelper.Mode[] capacityModes;
		public MathHelper.Mode[] adrModes;
		public double adrValue;
		public double capacityEstimateLower;
		public double capacityEstimateUpper;
		public double prevCapacityEstimateLower;
		public double prevCapacityEstimateUpper;
		public int canStop = 0;
	}

	public void install(ISink sink, IConnectionSpeedProvider connectionSpeedProvider);

	public void startAsSender(ICancelTask task) throws IOException, InterruptedException;

	public void startAsReceiver(InetAddress senderAddress, ICancelTask task) throws IOException, InterruptedException;

	public CapacityData getCapacityData();
}
