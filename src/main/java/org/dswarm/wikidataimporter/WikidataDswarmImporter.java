/**
 * Copyright (C) 2013 – 2015 SLUB Dresden & Avantgarde Labs GmbH (<code@dswarm.org>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dswarm.wikidataimporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.interfaces.Claim;
import org.wikidata.wdtk.datamodel.interfaces.DatatypeIdValue;
import org.wikidata.wdtk.datamodel.interfaces.ItemDocument;
import org.wikidata.wdtk.datamodel.interfaces.ItemIdValue;
import org.wikidata.wdtk.datamodel.interfaces.MonolingualTextValue;
import org.wikidata.wdtk.datamodel.interfaces.PropertyDocument;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;
import org.wikidata.wdtk.datamodel.interfaces.Reference;
import org.wikidata.wdtk.datamodel.interfaces.SiteLink;
import org.wikidata.wdtk.datamodel.interfaces.Snak;
import org.wikidata.wdtk.datamodel.interfaces.SnakGroup;
import org.wikidata.wdtk.datamodel.interfaces.StatementGroup;
import org.wikidata.wdtk.datamodel.interfaces.StatementRank;
import org.wikidata.wdtk.datamodel.interfaces.Value;
import org.wikidata.wdtk.datamodel.interfaces.ValueSnak;
import rx.Observable;

import org.dswarm.graph.json.LiteralNode;
import org.dswarm.graph.json.Node;
import org.dswarm.graph.json.NodeType;
import org.dswarm.graph.json.Predicate;
import org.dswarm.graph.json.Resource;
import org.dswarm.graph.json.ResourceNode;
import org.dswarm.graph.json.Statement;
import org.dswarm.graph.json.stream.ModelParser;

/**
 * @author tgaengler
 */
public class WikidataDswarmImporter {

	private static final Logger LOG = LoggerFactory.getLogger(WikidataDswarmImporter.class);

	public static final String LANGUAGE_CODE_EN                              = "en";
	public static final String CONFIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER     = "confidence";
	public static final String EVIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER       = "evidence";
	public static final String ORDER_QUALIFIED_ATTRIBUTE_IDENTIFIER          = "order";
	public static final String STATEMENT_UUID_QUALIFIED_ATTRIBUTE_IDENTIFIER = "statement uuid";
	public static final String MEDIAWIKI_PROPERTY_ID_PREFIX                  = "P";

	private final AtomicLong    resourceCount     = new AtomicLong();
	private final AtomicInteger propertyIdCounter = new AtomicInteger(100000);

	private static final Map<String, ItemIdValue>     gdmResourceURIWikidataItemMap     = new HashMap<>();
	private static final Map<String, PropertyIdValue> gdmPropertyURIWikidataPropertyMap = new HashMap<>();

	private final WikibaseAPIClient wikibaseAPIClient;

	public WikidataDswarmImporter() throws WikidataImporterException {

		wikibaseAPIClient = new WikibaseAPIClient();
	}

	public void importGDMModel(final String filePath) throws IOException {

		final Observable<Resource> gdmModel = getGDMModel(filePath);

		gdmModel.map(resource -> {

			try {

				processGDMResource(resource);
			} catch (final Exception e) {

				final String message = "something went wrong while processing this resource";

				LOG.error(message, e);

				throw WikidataImporterError.wrap(new WikidataImporterException(message, e));
			}

			return resource;
		}).toBlocking().firstOrDefault(null);

		// TODO: return Observable (?)
	}

	private void processGDMResource(final Resource resource) throws JsonProcessingException, WikidataImporterException {

		resourceCount.incrementAndGet();

		final List<MonolingualTextValue> labels = generateLabels(resource);

		final List<org.wikidata.wdtk.datamodel.interfaces.Statement> wikidataStatements = new ArrayList<>();

		final Set<Statement> gdmStatements = resource.getStatements();

		if (gdmStatements != null) {

			// write statements (if available)

			for (final Statement gdmStatement : gdmStatements) {

				final Optional<org.wikidata.wdtk.datamodel.interfaces.Statement> optionalWikidataStmt = processGDMStatement(gdmStatement);

				if (!optionalWikidataStmt.isPresent()) {

					// log non-created statements
					LOG.debug("could not process statement '{}'", printGDMStatement(gdmStatement));

					continue;
				}

				final org.wikidata.wdtk.datamodel.interfaces.Statement wikidataStmt = optionalWikidataStmt.get();

				wikidataStatements.add(wikidataStmt);
			}
		}

		final StatementGroup statementGroup = Datamodel.makeStatementGroup(wikidataStatements);
		final List<StatementGroup> statementGroups = new ArrayList<>();
		statementGroups.add(statementGroup);

		// we can also create an item with all it's statements at once, i.e., this would save some HTTP API calls
		// TODO: check ItemIdValue in map (i.e. whether an wikidata for this gdm resource exists already, or not)
		final ItemDocument wikidataItem = Datamodel.makeItemDocument(null, labels, null, null, statementGroups, null);

		// create item at wikibase (check whether statements are created as well - otherwise we need to create them separately)
		final Observable<Response> createEntityResponse = wikibaseAPIClient
				.createEntity(wikidataItem, WikibaseAPIClient.WIKIBASE_API_ENTITY_TYPE_ITEM);

		// TODO: evaluate response, e.g., cache item id somewhere
		final Response response = createEntityResponse.toBlocking().firstOrDefault(null);
	}

	/**
	 * sets the resource URI as label right now
	 *
	 * @param resource
	 * @return
	 */
	private List<MonolingualTextValue> generateLabels(final Resource resource) {

		final String resourceURI = resource.getUri();

		return generateLabels(resourceURI);
	}

	private Optional<org.wikidata.wdtk.datamodel.interfaces.Statement> processGDMStatement(final Statement statement) {

		final Predicate gdmPredicate = statement.getPredicate();
		final PropertyIdValue wikidataProperty = processGDMPredicate(gdmPredicate);

		final Node gdmObject = statement.getObject();
		final Optional<Value> optionalWikidataValue = processGDMObject(gdmObject);

		if (!optionalWikidataValue.isPresent()) {

			return Optional.empty();
		}

		final Value wikidataValue = optionalWikidataValue.get();

		// create property value pair
		final ValueSnak snak = Datamodel.makeValueSnak(wikidataProperty, wikidataValue);

		// process qualified attributes at GDM statement
		final Optional<List<Snak>> wikidataQualifiers = processGDMQualifiedAttributes(statement);

		final List<SnakGroup> snakGroups = new ArrayList<>();

		if (wikidataQualifiers.isPresent()) {

			final SnakGroup snakGroup = Datamodel.makeSnakGroup(wikidataQualifiers.get());

			snakGroups.add(snakGroup);
		}

		final Claim claim = Datamodel.makeClaim(null, snak, snakGroups);

		final List<Reference> references = new ArrayList<>();
		final StatementRank rank = StatementRank.NORMAL;

		// note: empty string for statement id (this should be utilised for statements that are created)
		// note: Statement references cannot be null
		// note: Statement rank cannot be null
		return Optional.ofNullable(Datamodel.makeStatement(claim, references, rank, ""));
	}

	private PropertyIdValue processGDMPredicate(final Predicate predicate) {

		final String predicateURI = predicate.getUri();

		return createOrGetWikidataProperty(predicateURI);
	}

	private PropertyIdValue createOrGetWikidataProperty(final String propertyIdentifier) {

		return gdmPropertyURIWikidataPropertyMap.computeIfAbsent(propertyIdentifier, propertyIdentifier1 -> {

			final List<MonolingualTextValue> labels = generateLabels(propertyIdentifier1);
			final List<MonolingualTextValue> descriptions = generateLabels(propertyIdentifier1);
			final List<MonolingualTextValue> aliases = new ArrayList<>();

			// add datatype - e.g. all literals are strings (DatatypeIdValue#DT_STRING) and all resources are items (DatatypeIdValue#DT_ITEM)
			final DatatypeIdValue datatypeIdValue = Datamodel.makeDatatypeIdValue(DatatypeIdValue.DT_STRING);

			// note: list of descriptions cannot be null
			// note: list of aliases cannot be null
			final PropertyDocument wikidataProperty = Datamodel.makePropertyDocument(null, labels, descriptions, aliases, datatypeIdValue);

			// create Property at Wikibase (to have a generated Property identifier)
			try {

				final Observable<Response> createEntityResponse = wikibaseAPIClient.createEntity(wikidataProperty,
						WikibaseAPIClient.WIKIBASE_API_ENTITY_TYPE_PROPERTY);

				final Response response = createEntityResponse.toBlocking().firstOrDefault(null);

				if (response == null) {

					final String message = String.format("could not create new property for '%s'", propertyIdentifier1);

					LOG.error(message);

					throw new WikidataImporterError(new WikidataImporterException(message));
				}

				final int status = response.getStatus();

				LOG.debug("response status = {}", status);

				final String responseBody = response.readEntity(String.class);

				LOG.debug("response body = {}", responseBody);
			} catch (final Exception e) {

				final String message2 = "something went wrong, while trying to create a new property";

				throw WikidataImporterError.wrap(new WikidataImporterException(message2, e));
			}

			// TODO: return property id from response, i.e., deserialize response body
			return wikidataProperty.getPropertyId();
		});
	}

	private Optional<Value> processGDMObject(final Node object) {

		final NodeType objectType = object.getType();

		switch (objectType) {

			case Literal:

				final LiteralNode literalNode = (LiteralNode) object;
				final String value = literalNode.getValue();

				return Optional.ofNullable(Datamodel.makeStringValue(value));
			case Resource:

				// create ItemIdValue, i.e., create a Wikidata Item just with the Id as label

				final ResourceNode resourceNode = (ResourceNode) object;

				return Optional.ofNullable(processGDMResourceNode(resourceNode));
			default:

				// TODO throw an exception or just skip and log (i.e. these should be bnodes)
		}

		return Optional.empty();
	}

	private Optional<List<Snak>> processGDMQualifiedAttributes(final Statement statement) {

		final List<Snak> snakList = new ArrayList<>();

		final Optional<Snak> optionalConfidence = processGDMQualifiedAttribute(CONFIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER, statement.getConfidence());
		final Optional<Snak> optionalEvidence = processGDMQualifiedAttribute(EVIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER, statement.getEvidence());
		final Optional<Snak> optionalOrder = processGDMQualifiedAttribute(ORDER_QUALIFIED_ATTRIBUTE_IDENTIFIER, statement.getOrder());

		// D:SWARM statement uuid
		final Optional<Snak> optionalUUID = processGDMQualifiedAttribute(STATEMENT_UUID_QUALIFIED_ATTRIBUTE_IDENTIFIER, statement.getUUID());

		addToSnakList(optionalConfidence, snakList);
		addToSnakList(optionalEvidence, snakList);
		addToSnakList(optionalOrder, snakList);
		addToSnakList(optionalUUID, snakList);

		if (snakList.isEmpty()) {

			return Optional.empty();
		}

		return Optional.of(snakList);
	}

	private Optional<Snak> processGDMQualifiedAttribute(final String qualifiedAttributeIdentifier, final Object qualifiedAttributeValue) {

		if (qualifiedAttributeValue == null) {

			return Optional.empty();
		}

		final PropertyIdValue wikidataProperty = createOrGetWikidataProperty(qualifiedAttributeIdentifier);

		final Value value;

		switch (qualifiedAttributeIdentifier) {

			case CONFIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER:
			case EVIDENCE_QUALIFIED_ATTRIBUTE_IDENTIFIER:
			case STATEMENT_UUID_QUALIFIED_ATTRIBUTE_IDENTIFIER:

				// string

				value = Datamodel.makeStringValue((String) qualifiedAttributeValue);

				break;
			case ORDER_QUALIFIED_ATTRIBUTE_IDENTIFIER:

				// long

				// TODO: no number/long specific datatype available?

				// order as string for now (maybe this qualified attribute is not really needed)
				value = Datamodel.makeStringValue((String) qualifiedAttributeValue);

				break;
			default:

				LOG.debug("found an unknown qualified attribute '{}'", qualifiedAttributeIdentifier);

				return Optional.empty();
		}

		final Snak snak = Datamodel.makeValueSnak(wikidataProperty, value);

		return Optional.of(snak);
	}

	private void addToSnakList(final Optional<Snak> optionalSnak, final List<Snak> snakList) {

		if (optionalSnak.isPresent()) {

			snakList.add(optionalSnak.get());
		}
	}

	private ItemIdValue processGDMResourceNode(final ResourceNode resourceNode) {

		final String resourceURI = resourceNode.getUri();

		return gdmResourceURIWikidataItemMap.computeIfAbsent(resourceURI, resourceURI1 -> {

			final List<MonolingualTextValue> labels = generateLabels(resourceURI);
			final List<MonolingualTextValue> descriptions = generateLabels(resourceURI1);
			final List<MonolingualTextValue> aliases = new ArrayList<>();
			final List<StatementGroup> statementGroups = new ArrayList<>();
			final Map<String, SiteLink> siteLinkMap = new HashMap<>();

			// note: list of descriptions cannot be null
			// note: list of aliases cannot be null
			// note: list of statement groups cannot be null
			final ItemDocument wikidataItem = Datamodel.makeItemDocument(null, labels, descriptions, aliases, statementGroups, siteLinkMap);

			// create Item at Wikibase (to have a generated Item identifier)
			try {

				final Observable<Response> createEntityResponse = wikibaseAPIClient
						.createEntity(wikidataItem, WikibaseAPIClient.WIKIBASE_API_ENTITY_TYPE_ITEM);

				final Response response = createEntityResponse.toBlocking().firstOrDefault(null);
			} catch (final Exception e) {

				final String message = "something went wrong, while trying to create a new item";

				LOG.error(message, e);

				throw WikidataImporterError.wrap(new WikidataImporterException(message, e));
			}

			// TODO: return item id from response item
			return wikidataItem.getItemId();
		});
	}

	private static Observable<Resource> getGDMModel(final String filePath) throws IOException {

		final InputStream gdmModelStream = getGDMModelStream(filePath);

		final ModelParser modelParser = new ModelParser(gdmModelStream);

		return modelParser.parse();
	}

	private static InputStream getGDMModelStream(final String filePath) throws IOException {

		LOG.debug("try to open input file @ '{}'", filePath);

		final Path path = Paths.get(filePath);

		return Files.newInputStream(path);
	}

	private List<MonolingualTextValue> generateLabels(final String sourceLabel) {

		final List<MonolingualTextValue> labels = new ArrayList<>();
		final MonolingualTextValue label = Datamodel.makeMonolingualTextValue(sourceLabel, LANGUAGE_CODE_EN);

		labels.add(label);

		return labels;
	}

	private static String printGDMStatement(final Statement statement) {

		final StringBuilder sb = new StringBuilder();

		final Long id = statement.getId();

		sb.append("{statement: id ='");

		if (id != null) {

			sb.append(id);
		} else {

			sb.append("no statement id available");
		}

		sb.append("' :: ");

		final String uuid = statement.getUUID();

		sb.append("uuid = '");

		if (uuid != null) {

			sb.append(uuid);
		} else {

			sb.append("no uuid available");
		}

		sb.append("' :: ");

		final String subject = printGDMNode(statement.getSubject());

		sb.append("subject = '").append(subject).append("' :: ");

		final String predicateURI = statement.getPredicate().getUri();

		sb.append("predicate = '").append(predicateURI).append("' :: ");

		final String object = printGDMNode(statement.getObject());

		sb.append("object = '").append(object).append("'}");

		return sb.toString();
	}

	private static String printGDMNode(final Node node) {

		final StringBuilder sb = new StringBuilder();

		final Long id = node.getId();

		sb.append("id = '");

		if (id != null) {

			sb.append(id);
		} else {

			sb.append("no node id available");
		}

		final NodeType nodeType = node.getType();

		switch (nodeType) {

			case Literal:

				sb.append("' :: ");

				final LiteralNode literalNode = (LiteralNode) node;
				final String value = literalNode.getValue();

				sb.append("value = '").append(value);

				break;
			case Resource:

				sb.append("' :: ");

				final ResourceNode resourceNode = (ResourceNode) node;
				final String resourceURI = resourceNode.getUri();

				sb.append("uri = '").append(resourceURI);

				break;
		}

		sb.append("' :: type = '").append(nodeType).append("'}");

		return sb.toString();
	}
}
