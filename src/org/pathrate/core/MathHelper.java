/*
 * This file is part of Pathrate, an end-to-end capacity estimation tool
 * Copyright (C) 2002-2013
 *  Constantinos Dovrolis    <dovrolis@cc.gatech.edu>
 *  Ravi S Prasad            <ravi@cc.gatech.edu>
 *  Antonio Macrì            <ing.antonio.macri@gmail.com>
 *  Francesco Racciatti      <francesco.racciatti@gmail.com>
 *  Silvia Volpe             <silvia.volpe88@gmail.com>           

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.pathrate.core;

import java.util.Arrays;

public class MathHelper
{
	public static final double INVALID_KURTOSIS = Double.NaN;
	public static final int BIN_NOISE = 10;
	public static final int MAX_NUMBER_OF_MODES = 10;
	private static final double BIN_CNT_TOLER_kernel_percent = 0.1;

	public static class Mode
	{
		/**
		 * Lower bandwidth value (Mbps) of local mode.
		 */
		public double modeLowerValue;
		/**
		 * Upper bandwidth value (Mbps) of local mode.
		 */
		public double modeUpperValue;
		/**
		 * Number of measurements in local mode.
		 */
		public int modeCount;
		/**
		 * Low bandwidth threshold (Mbps) of modal bell.
		 */
		public double bellLowerThreshold;
		/**
		 * High bandwidth threshold (Mbps) of modal bell.
		 */
		public double bellUpperThreshold;
		/**
		 * Number of measurements in "bell" of local mode
		 */
		public int bellCount;
		/**
		 * Total number of measurements from which the mode was extracted.
		 */
		public int totalCount;
		public double bellKurtosis;
	}

	/**
	 * Compute kurtosis' index of the given set of measurements. Requires at least 3 samples.
	 * 
	 * @param measurements
	 *            an array containing the measurements
	 * @param start
	 *            the index from which starts the bell whose kurtosis is to be calculated
	 * @param end
	 *            the index to which ends the bell whose kurtosis is to be calculated
	 * @return the calculated kurtosis or INVALID_KURTOSIS in case of error
	 */
	private static double getKurtosis(double[] measurements, int start, int end)
	{
		int size = end - start + 1;
		if (size < 3) {
			return INVALID_KURTOSIS;
		}

		double average = getAverage(measurements, start, size);
		double numerator = 0, denominator = 0;

		for (int i = start; i < start + size; i++) {
			double diff = measurements[i] - average;
			diff *= diff;
			denominator += diff;
			numerator += diff * diff;
		}

		if (denominator == 0) {
			return INVALID_KURTOSIS;
		}
		denominator /= size;

		return numerator / (denominator * denominator);
	}

	/**
	 * Compute the average of the given set of measurements.
	 */
	public static double getAverage(double data[], int start, int count)
	{
		double sum = 0;
		for (int i = start; i < start + count; i++) {
			sum += data[i];
		}
		return sum / count;
	}

	/**
	 * Compute the standard deviation of the given set of measurements.
	 */
	public static double getStandardDeviation(double[] data, int start, int count)
	{
		double sum = 0, average = getAverage(data, start, count);
		for (int i = start; i < start + count; i++) {
			sum += Math.pow(data[i] - average, 2.);
		}
		return Math.sqrt(sum / (count - 1));
	}

	public static double calculateBinWidth(double[] sortedValues)
	{
		if (sortedValues.length < 10) {
			return 1;
		}
		double q1 = sortedValues[sortedValues.length / 4];
		double q2 = sortedValues[3 * sortedValues.length / 4];
		return Math.max(1, (q2 - q1) / 10);
	}

	public static int[] calculateDistribution(int[] sortedValues, int binWidth)
	{
		int max = sortedValues[sortedValues.length - 1];
		int[] distribution = new int[(1 + max + binWidth - 1) / binWidth];
		for (int value : sortedValues) {
			distribution[value / binWidth]++;
		}
		return distribution;
	}

	public static int[] calculateDistribution(double[] sortedValues, double binWidth)
	{
		if (sortedValues.length == 0) {
			return new int[] { 0 };
		}
		double max = sortedValues[sortedValues.length - 1];
		// Always add an additional row, required by pgfplots when plotting histograms
		int[] distribution = new int[(int) ((1 + max + binWidth - 1) / binWidth) + 1];
		for (double value : sortedValues) {
			distribution[(int) (value / binWidth)]++;
		}
		distribution[distribution.length - 1] = 0;
		return distribution;
	}

	public static int[] calculateCCDF(int[] occurrencies)
	{
		int[] result = new int[occurrencies.length];
		for (int i = occurrencies.length - 2; i >= 0; i--) {
			result[i] = occurrencies[i + 1] + result[i + 1];
		}
		// We suppose values[0] is always 0
		return result;
	}

	/**
	 * Detect a local mode in the given set of measurements, taking into account only the valid (unmarked) ones. Modes
	 * are returned in decreasing order of {@link Mode.modeCount}.
	 * 
	 * @param sortedValues
	 *            an array of measurements sorted in increasing order
	 * @param dataValid
	 *            an array of booleans specifying whether the corresponding value is to be considered valid
	 * @param binWidth
	 *            the bin width to use in the local mode detection process
	 * @return the mode detected, or {@link UNIMPORTANT_MODE}, or null if no more modes can be extracted
	 */
	public static Mode extractMode(double[] sortedValues, boolean[] dataValid, double binWidth)
	{
		// Find the bin of the primary mode from non-marked values:
		// find window of length binWidth with maximum number of consecutive values
		int modeStartIndex = 0, modeEndIndex = 0;
		for (int i = 0, count = 0; i < dataValid.length; i++) {
			if (dataValid[i]) {
				int j = i;
				double max = sortedValues[i] + binWidth;
				while (j < dataValid.length && dataValid[j] && sortedValues[j] <= max) {
					j++;
				}
				if (count < j - i) {
					count = j - i;
					modeStartIndex = i;
					modeEndIndex = j - 1;
				}
			}
		}
		if (modeEndIndex == 0) {
			return null; // no more modes
		}

		Mode currentMode = new Mode();
		currentMode.totalCount = sortedValues.length;
		currentMode.modeCount = modeEndIndex - modeStartIndex + 1;
		currentMode.modeLowerValue = sortedValues[modeStartIndex];
		currentMode.modeUpperValue = sortedValues[modeEndIndex];

		currentMode.bellCount = currentMode.modeCount;
		currentMode.bellLowerThreshold = currentMode.modeLowerValue;
		currentMode.bellUpperThreshold = currentMode.modeUpperValue;
		int bellStartIndex = modeStartIndex;
		int bellEndIndex = modeEndIndex;

		// Noise tolerance is determined by binCountTolerance, and it's
		// proportional to previous binCount instead of constant BIN_NOISE_TOLER.
		double binCountTolerance;

		// Find all the bins at the *left* of the central bin that are part of
		// the same mode's bell. Stop when another local mode is detected.
		int binCount = currentMode.modeCount;
		int binStartIndex = modeStartIndex;
		int binEndIndex = modeEndIndex;
		binCountTolerance = BIN_CNT_TOLER_kernel_percent * (binCount);
		do {
			int leftBinCount = 0, leftBinStartIndex = 0, leftBinEndIndex = 0;
			if (binStartIndex > 0) {
				for (int i = binEndIndex - 1; i >= binStartIndex - 1; i--) {
					int j = findLastLess(sortedValues, 0, i + 1, sortedValues[i] - binWidth);
					if (i - j >= leftBinCount) {
						leftBinCount = i - j;
						leftBinStartIndex = j + 1;
						leftBinEndIndex = i;
					}
				}
			}

			if (leftBinCount <= 0) {
				break;
			}
			if (leftBinCount < binCount + binCountTolerance) {
				currentMode.bellCount += binStartIndex - leftBinStartIndex;
				currentMode.bellLowerThreshold = sortedValues[leftBinStartIndex];
				bellStartIndex = leftBinStartIndex;

				binCount = leftBinCount;
				binStartIndex = leftBinStartIndex;
				binEndIndex = leftBinEndIndex;
				binCountTolerance = BIN_CNT_TOLER_kernel_percent * (binCount);
			}
			else {
				// the bin is outside the modal bell
				break;
			}
			if (binStartIndex <= 1) {
				break;
			}
		} while (true);

		// Find all the bins at the *right* of the central bin that are part of
		// the same mode's bell. Stop when another local mode is detected.
		binCount = currentMode.modeCount;
		binStartIndex = modeStartIndex;
		binEndIndex = modeEndIndex;
		do {
			int rightBinCount = 0, rightBinStartIndex = 0, rightBinEndIndex = 0;
			if (binEndIndex < sortedValues.length - 1) {
				for (int i = binStartIndex + 1; i <= binEndIndex + 1; i++) {
					int j = findFirstGreater(sortedValues, i, sortedValues.length, sortedValues[i] + binWidth);
					if (j - i >= rightBinCount) {
						rightBinCount = j - i;
						rightBinStartIndex = i;
						rightBinEndIndex = j - 1;
					}
				}
			}

			if (rightBinCount <= 0) {
				break;
			}
			if (rightBinCount < binCount + binCountTolerance) {
				currentMode.bellCount += rightBinEndIndex - binEndIndex;
				currentMode.bellUpperThreshold = sortedValues[rightBinEndIndex];
				bellEndIndex = rightBinEndIndex;

				binCount = rightBinCount;
				binStartIndex = rightBinStartIndex;
				binEndIndex = rightBinEndIndex;
				binCountTolerance = BIN_CNT_TOLER_kernel_percent * (binCount);
			}
			else {
				// the bin is outside the modal bell
				break;
			}
			if (rightBinEndIndex >= dataValid.length - 2) {
				break;
			}
		} while (true);

		// Mark the values that make up this modal bell as invalid
		Arrays.fill(dataValid, bellStartIndex, bellEndIndex + 1, false);

		if (currentMode.modeCount <= BIN_NOISE) {
			// Unimportant mode: try another one
			return extractMode(sortedValues, dataValid, binWidth);
		}
		currentMode.bellKurtosis = getKurtosis(sortedValues, bellStartIndex, bellEndIndex);
		if (currentMode.bellKurtosis == INVALID_KURTOSIS) {
			// Unimportant mode: try another one
			return extractMode(sortedValues, dataValid, binWidth);
		}
		return currentMode;
	}

	private static int findLastLess(double[] sortedValues, int start, int end, double value)
	{
		while (start != end) {
			int middle = (end + start) / 2;
			if (sortedValues[middle] >= value) {
				end = middle;
			}
			else {
				start = middle + 1;
			}
		}
		return start - 1;
	}

	private static int findFirstGreater(double[] sortedValues, int start, int end, double value)
	{
		while (start != end) {
			int middle = (end + start) / 2;
			if (sortedValues[middle] <= value) {
				start = middle + 1;
			}
			else {
				end = middle;
			}
		}
		return end;
	}

	/**
	 * Retrieves an array containing a list of modes extracted from the given set of measurements using the specified
	 * bin width. Modes are ordered based on the number of measurements in the modal bin (strongest mode first).
	 * 
	 * @param sortedValues
	 *            an array of measurements sorted in increasing order
	 * @param binWidth
	 *            the bin width to use in the local mode detection process
	 * @return an array containing the calculated modes
	 */
	public static MathHelper.Mode[] calculateModes(double[] sortedValues, double binWidth)
	{
		MathHelper.Mode[] modes = new MathHelper.Mode[MAX_NUMBER_OF_MODES];
		int count = 0;

		// Mark all measurements as valid
		boolean[] validCapacities = new boolean[sortedValues.length];
		Arrays.fill(validCapacities, true);

		MathHelper.Mode currentMode;
		while (count < modes.length
				&& (currentMode = MathHelper.extractMode(sortedValues, validCapacities, binWidth)) != null) {
			modes[count++] = currentMode;
		}
		return ArrayHelper.copyOf(modes, count);
	}
}
