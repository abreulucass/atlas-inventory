package io.github.abreulucass.atlas.stock;

/** The company, warehouse or product of a movement is archived: archive freezes new use, not history. */
public class StockArchivedException extends RuntimeException {

	public StockArchivedException(String what) {
		super("The " + what + " is archived and takes no new movements");
	}

}
