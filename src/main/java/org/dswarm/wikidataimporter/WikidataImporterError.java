package org.dswarm.wikidataimporter;

import rx.functions.Action0;
import rx.functions.Func1;

/**
 * @author tgaengler
 */
public class WikidataImporterError extends RuntimeException {

	public WikidataImporterError(final Throwable cause) {
		super(cause);
	}

	public static WikidataImporterError wrap(final WikidataImporterException exception) {
		return new WikidataImporterError(exception);
	}

	public static Action0 wrapped(final PersistenceAction action) {
		return () -> {
			try {
				action.run();
			} catch (WikidataImporterException e) {
				throw wrap(e);
			}
		};
	}

	public static <T, R> Func1<T, R> wrapped(final PersistenceFunction1<T, R> func) {
		return t -> {
			try {
				return func.apply(t);
			} catch (WikidataImporterException e) {
				throw wrap(e);
			}
		};
	}

	@FunctionalInterface
	public interface PersistenceAction {

		void run() throws WikidataImporterException;
	}

	@FunctionalInterface
	public interface PersistenceFunction1<T, R> {

		R apply(final T t) throws WikidataImporterException;
	}
}
