package org.dswarm.wikidataimporter;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.rx.RxWebTarget;
import org.glassfish.jersey.client.rx.rxjava.RxObservable;
import org.glassfish.jersey.client.rx.rxjava.RxObservableInvoker;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.interfaces.EntityDocument;
import rx.Observable;
import rx.schedulers.Schedulers;

/**
 * @author tgaengler
 */
public class WikibaseAPIClient {

	private static final Logger LOG = LoggerFactory.getLogger(WikibaseAPIClient.class);

	private static final String wikibaseAPIBaseURI;
	public static final String DSWARM_USER_AGENT_IDENTIFIER = "DMP 2000";

	static {

		final URL resource = Resources.getResource("dswarm.properties");
		final Properties properties = new Properties();

		try {

			properties.load(resource.openStream());
		} catch (final IOException e) {

			LOG.error("Could not load dswarm.properties", e);
		}

		wikibaseAPIBaseURI = properties.getProperty("wikibase_api_endpoint", "http://localhost:1234/whoknows");
	}

	private static final String CHUNKED = "CHUNKED";

	private static final int CHUNK_SIZE      = 1024;
	private static final int REQUEST_TIMEOUT = 20000000;

	private static final String          DSWARM_WIKIDATA_GDM_IMPORTER_THREAD_NAMING_PATTERN = "dswarm-wikidata-gdm-importer-%d";
	private static final ExecutorService EXECUTOR_SERVICE                                   = Executors.newCachedThreadPool(
			new BasicThreadFactory.Builder().daemon(false).namingPattern(DSWARM_WIKIDATA_GDM_IMPORTER_THREAD_NAMING_PATTERN).build());

	private static final ClientBuilder BUILDER = ClientBuilder.newBuilder().register(MultiPartFeature.class)
			.property(ClientProperties.CHUNKED_ENCODING_SIZE, CHUNK_SIZE)
			.property(ClientProperties.REQUEST_ENTITY_PROCESSING, CHUNKED)
			.property(ClientProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, CHUNK_SIZE)
			.property(ClientProperties.CONNECT_TIMEOUT, REQUEST_TIMEOUT)
			.property(ClientProperties.READ_TIMEOUT, REQUEST_TIMEOUT);

	private static final String WIKIBASE_API_ACTION_IDENTIFIER = "action";
	private static final String WIKIBASE_API_NEW_IDENTIFIER    = "new";
	private static final String WIKIBASE_API_DATA_IDENTIFIER   = "data";

	private static final String WIKIBASE_API_ITEM_IDENTIFIER = "item";

	private static final String WIKIBASE_API_EDIT_ENTITY = "wbeditentity";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static Observable<Response> createEntity(final EntityDocument entity) throws JsonProcessingException {

		final String entityJSONString = MAPPER.writeValueAsString(entity);

		final RxWebTarget<RxObservableInvoker> rxWebTarget = rxWebTarget();

		// TODO: which accept media type? - add to request method
		final RxObservableInvoker rx = rxWebTarget.queryParam(WIKIBASE_API_ACTION_IDENTIFIER, WIKIBASE_API_EDIT_ENTITY)
				.queryParam(WIKIBASE_API_NEW_IDENTIFIER, WIKIBASE_API_ITEM_IDENTIFIER)
				.queryParam(WIKIBASE_API_DATA_IDENTIFIER, entityJSONString)
				.request()
				.header(HttpHeaders.USER_AGENT, DSWARM_USER_AGENT_IDENTIFIER).rx();

		final Observable<Response> post = rx.post(entity).subscribeOn(Schedulers.from(EXECUTOR_SERVICE));
	}

	private static Client client() {

		return BUILDER.build();
	}

	private static WebTarget target() {

		return client().target(wikibaseAPIBaseURI);
	}

	private static WebTarget target(final String... path) {

		WebTarget target = target();

		for (final String p : path) {

			target = target.path(p);
		}

		return target;
	}

	private static RxWebTarget<RxObservableInvoker> rxWebTarget() {

		final WebTarget target = target();

		return RxObservable.from(target);
	}

	private static RxWebTarget<RxObservableInvoker> rxWebTarget(final String... path) {

		final WebTarget target = target(path);

		return RxObservable.from(target);
	}
}
