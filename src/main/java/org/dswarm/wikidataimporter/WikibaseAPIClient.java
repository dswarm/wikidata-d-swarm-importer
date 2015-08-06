package org.dswarm.wikidataimporter;

import java.io.IOException;
import java.net.URL;
import java.util.Observable;
import java.util.Properties;

import com.google.common.io.Resources;
import net.sourceforge.jwbf.mediawiki.bots.MediaWikiBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.interfaces.EntityDocument;

/**
 * @author tgaengler
 */
public class WikibaseAPIClient {

	private static final Logger LOG = LoggerFactory.getLogger(WikibaseAPIClient.class);

	private static final String MEDIAWIKI_API_BASE_URI;
	public static final String DSWARM_USER_AGENT_IDENTIFIER = "DMP 2000";

	static {

		final URL resource = Resources.getResource("dswarm.properties");
		final Properties properties = new Properties();

		try {

			properties.load(resource.openStream());
		} catch (final IOException e) {

			LOG.error("Could not load dswarm.properties", e);
		}

		MEDIAWIKI_API_BASE_URI = properties.getProperty("wikibase_api_endpoint", "http://localhost:1234/whoknows");
	}

	private static final String WIKIBASE_API_NEW_IDENTIFIER    = "new";
	private static final String WIKIBASE_API_DATA_IDENTIFIER   = "data";

	private static final String WIKIBASE_API_ITEM_IDENTIFIER = "item";

	private static final String WIKIBASE_API_EDIT_ENTITY = "wbeditentity";

	private static final boolean createEntity(final EntityDocument entityDocument) {

		final MediaWikiBot client = client();

		client.
	}

	private static MediaWikiBot client() {

		return new MediaWikiBot(MEDIAWIKI_API_BASE_URI);
	}

}
