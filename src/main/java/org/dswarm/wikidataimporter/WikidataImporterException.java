package org.dswarm.wikidataimporter;

/**
 * @author tgaengler
 */
public class WikidataImporterException extends Exception {

	/**
	 *
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a new Wikidata import exception with the given exception message.
	 *
	 * @param exception the exception message
	 */
	public WikidataImporterException(final String exception) {

		super(exception);
	}

	/**
	 * Creates a new TPU exception with the given exception message
	 * and a cause.
	 *
	 * @param message the exception message
	 * @param cause   a previously thrown exception, causing this one
	 */
	public WikidataImporterException(final String message, final Throwable cause) {

		super(message, cause);
	}
}
