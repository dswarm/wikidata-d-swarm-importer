package org.dswarm.wikidataimporter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author tgaengler
 */
public class Executer {

	private static final Logger LOG = LoggerFactory.getLogger(Executer.class);

	private static void executeImport(final String filePath) throws IOException, WikidataImporterException {

		final WikidataDswarmImporter wikidataDswarmImporter = new WikidataDswarmImporter();

		wikidataDswarmImporter.importGDMModel(filePath);
	}

	public static void main(final String[] args) {

		// 0. read path from arguments
		if (args == null || args.length <= 0) {

			LOG.error("cannot execute import - no file path given as commandline parameter");

			return;
		}

		final String filePath = args[0];

		try {

			executeImport(filePath);
		} catch (final Exception e) {

			LOG.error("something went wrong at import execution.", e);
		}
	}
}
