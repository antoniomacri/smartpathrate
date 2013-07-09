/**
 * @author Antonio Macrì, Francesco Racciatti, Silvia Volpe
 */
package org.pathrate.core;

public interface ISink
{
	public void debug(String message);

	public void info(String message);

	public void warning(String message);

	public void error(String message);
}
