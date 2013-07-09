package org.pathrate.core;

public interface IConnectionSpeedProvider
{
	/**
	 * Detects the speed of the Wi-Fi interface currently active.
	 * 
	 * @return the nominal speed expressed in microseconds of the active Wi-Fi interface
	 */
	public double detectWifiSpeed();
}
