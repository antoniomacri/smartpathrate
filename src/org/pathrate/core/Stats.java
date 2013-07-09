package org.pathrate.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

public class Stats
{
	// Use dot as decimal separator
	public static Locale locale = Locale.US;

	public static boolean statisticsEnabled = true;

	private static File root = new File(".");
	private static File overriddenResultsFolder;
	private static File folder;
	private static FileOutputStream foMeasurements;
	private static FileOutputStream foParams;
	private static FileOutputStream foResults;
	private static PrintWriter writerMeasurements;
	private static PrintWriter writerParams;
	private static PrintWriter writerResults;

	private static HashMap<String, Integer> map = new HashMap<String, Integer>();

	/**
	 * Sets the root folder where all statistics will be saved. It takes effect only before any write operation. If this
	 * method is not invoked, then the root folder is assumed to be the working directory
	 * 
	 * @param rootFolder
	 *            a string containing the full path of the root folder
	 */
	public static void setRootFolder(String rootFolder)
	{
		root = new File(rootFolder);
	}

	public static void setResultsFolder(String folder)
	{
		overriddenResultsFolder = new File(folder);
	}

	public static void reset() throws IOException
	{
		if (writerMeasurements != null) {
			writerMeasurements.close();
			writerMeasurements = null;
		}
		if (writerParams != null) {
			writerParams.close();
			writerParams = null;
		}
		if (writerResults != null) {
			writerResults.close();
			writerResults = null;
		}
		if (foMeasurements != null) {
			foMeasurements.close();
			foMeasurements = null;
		}
		if (foParams != null) {
			foParams.close();
			foParams = null;
		}
		if (foResults != null) {
			foResults.close();
			foResults = null;
		}
		folder = null;
		map.clear();
	}

	private static void checkMainFolderCreated()
	{
		if (folder != null) {
			return;
		}
		Calendar cal = Calendar.getInstance();
		int year = cal.get(Calendar.YEAR);
		int month = cal.get(Calendar.MONTH);
		int day = cal.get(Calendar.DAY_OF_MONTH);
		String directory = String.format(locale, "%d.%02d.%02d", year, (month + 1), day);

		for (int i = 1;; i++) {
			folder = new File(root, directory + "-" + i);
			if (!folder.exists()) break;
		}
		folder.mkdir();
	}

	private static void checkMeasurementsCreated() throws FileNotFoundException
	{
		if (writerMeasurements == null) {
			checkMainFolderCreated();
			File file = new File(folder, "measurements.tex");
			foMeasurements = new FileOutputStream(file, false);
			writerMeasurements = new PrintWriter(foMeasurements);
		}
	}

	private static void checkParamsCreated() throws FileNotFoundException
	{
		if (writerParams == null) {
			checkMainFolderCreated();
			File file = new File(folder, "params.tex");
			foParams = new FileOutputStream(file, false);
			writerParams = new PrintWriter(foParams);
		}
	}

	private static void checkResultsCreated() throws FileNotFoundException
	{
		if (writerResults == null) {
			File file;
			if (overriddenResultsFolder == null) {
				checkMainFolderCreated();
				file = new File(folder, "results.tex");
			}
			else {
				file = new File(overriddenResultsFolder, "results.tex");
			}
			foResults = new FileOutputStream(file, false);
			writerResults = new PrintWriter(foResults);
		}
	}

	private static void writePlotData(PrintWriter writer, String identifier, int[] values, boolean subtractFirst,
			double xMultiplier) throws FileNotFoundException
	{
		int zero = subtractFirst && values.length > 0 ? values[0] : 0;
		writer.println("\\measurement{" + getIndentifier(identifier) + "}{");
		String format = xMultiplier == Math.rint(xMultiplier) ? " %.0f\t%d \\\\" : " %.3f\t%d \\\\";
		for (int i = 0; i < values.length; i++) {
			writer.format(locale, format, (i * xMultiplier), (values[i] - zero));
			writer.println();
		}
		writer.println("}");
		writer.flush();
	}

	private static void writeListData(PrintWriter writer, String identifier, String[] items)
	{
		writer.print("\\@namedef{" + getIndentifier(identifier) + "}{");
		for (int i = 0; i < items.length; i++) {
			if (i > 0) writer.print(",");
			writer.println();
			writer.print(" " + items[i]);
		}
		writer.println();
		writer.println("}");
		writer.flush();
	}

	private static String getIndentifier(String identifier)
	{
		int index = 0;
		if (map.containsKey(identifier)) {
			index = (int) map.get(identifier);
		}
		map.put(identifier, index + 1);
		return String.format(locale, identifier, index);
	}

	//
	// Measurements
	//

	public static void saveTimestamps(int[] timestamps) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkMeasurementsCreated();
		writePlotData(writerMeasurements, "%d", timestamps, true, 1.0);
	}

	//
	// Params
	//

	public static void writeParam(String identifier, Object value) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkParamsCreated();
		writerParams.println("\\@namedef{" + getIndentifier(identifier) + "}{" + value.toString() + "}");
		writerParams.flush();
	}

	public static void writeParam(String identifier, String format, Object... args) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkParamsCreated();
		writerParams.println("\\@namedef{" + getIndentifier(identifier) + "}{" + String.format(locale, format, args)
				+ "}");
		writerParams.flush();
	}

	//
	// Results
	//

	public static void writeResult(String identifier, Object value) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writerResults.println("\\@namedef{" + getIndentifier(identifier) + "}{" + value.toString() + "}");
		writerResults.flush();
	}

	public static void writeResult(String identifier, String format, Object... args) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writerResults.println("\\@namedef{" + getIndentifier(identifier) + "}{" + String.format(locale, format, args)
				+ "}");
		writerResults.flush();
	}

	public static void writeRawResults(String data) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writerResults.println(data);
		writerResults.flush();
	}

	public static void writePlotData(String identifier, int[] values) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writePlotData(writerResults, identifier, values, false, 1.0);
	}

	public static void writePlotData(String identifier, int[] values, double xMultiplier) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writePlotData(writerResults, identifier, values, false, xMultiplier);
	}

	public static void writeListData(String identifier, String[] items) throws FileNotFoundException
	{
		if (!statisticsEnabled) {
			return;
		}
		checkResultsCreated();
		writeListData(writerResults, identifier, items);
	}

	public static void writeModes(String identifier, MathHelper.Mode[] modes) throws FileNotFoundException
	{
		String[] items = new String[modes.length];
		for (int i = 0; i < modes.length; i++) {
			MathHelper.Mode m = modes[i];
			double lmode = m.modeLowerValue, rmode = m.modeUpperValue;
			double lbell = m.bellLowerThreshold, rbell = m.bellUpperThreshold;
			int mcount = m.modeCount, bcount = m.bellCount;
			double kurtosis = m.bellKurtosis;
			// \lmode/\rmode / \lbell/\rbell / \mcount/\bcount / \kurtosis
			items[i] = String.format(locale, "%.2f/%.2f/%.2f/%.2f/%d/%d/%.2f", lmode, rmode, lbell, rbell, mcount,
					bcount, kurtosis);
		}
		Stats.writeListData("modes@" + identifier, items);
	}

	public static void writePerMeasurementJumps(int[] timestamps, int[][] jumps) throws FileNotFoundException
	{
		String[] items = new String[jumps[0].length];
		for (int i = 0; i < jumps[0].length; i++) {
			int first = jumps[0][i], last = first + jumps[1][i] - 1, prevOrZero = first == 0 ? 0 : first - 1;
			int yfirst = timestamps[first] - timestamps[0];
			int ylast = timestamps[last] - timestamps[0];
			// Don't swap order of operations below
			int ellipsecenter = ((timestamps[first] - timestamps[0]) + (timestamps[prevOrZero] - timestamps[0])) / 2;
			int ellipseyradius = timestamps[first] - timestamps[prevOrZero];
			// \xa / \ya / \xb / \yb / \ellipsecenter / \ellipseyradius
			items[i] = String.format(locale, "%d/%d/%d/%d/%d/%d", first, yfirst, last, ylast, ellipsecenter,
					ellipseyradius);
		}
		Stats.writeListData("jumps@%d", items);
	}
}
