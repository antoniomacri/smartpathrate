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

public class ArrayHelper
{
	public static double[] concat(double[]... arrays)
	{
		double[] result;
		int length = 0;
		for (double[] array : arrays) {
			length += array.length;
		}
		result = new double[length];
		int count = 0;
		for (int i = 0; i < arrays.length; i++) {
			System.arraycopy(arrays[i], 0, result, count, arrays[i].length);
			count += arrays[i].length;
		}
		return result;
	}

	public static <T> boolean contains(boolean[] dataValid, boolean b)
	{
		for (int i = dataValid.length - 1; i >= 0; i--) {
			if (dataValid[i] == b) {
				return true;
			}
		}
		return false;
	}

	public static int count(boolean[] array, boolean value)
	{
		int count = 0;
		for (int i = array.length - 1; i >= 0; i--) {
			if (array[i] == value) {
				count++;
			}
		}
		return count;
	}

	public static double[] mergeSortedArrays(double[] a, double[] b)
	{
		double[] answer = new double[a.length + b.length];
		int i = 0, j = 0, k = 0;
		while (i < a.length && j < b.length) {
			if (a[i] < b[j]) {
				answer[k] = a[i];
				i++;
			}
			else {
				answer[k] = b[j];
				j++;
			}
			k++;
		}
		while (i < a.length) {
			answer[k] = a[i];
			i++;
			k++;
		}
		while (j < b.length) {
			answer[k] = b[j];
			j++;
			k++;
		}
		return answer;
	}

	public static <T> T[] copyOf(T[] array, int count)
	{
		return Arrays.copyOf(array, count);
	}
}
