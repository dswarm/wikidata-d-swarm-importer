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
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.interfaces.Claim;
import org.wikidata.wdtk.datamodel.interfaces.ItemDocument;
import org.wikidata.wdtk.datamodel.interfaces.ItemIdValue;
import org.wikidata.wdtk.datamodel.interfaces.MonolingualTextValue;
import org.wikidata.wdtk.datamodel.interfaces.PropertyDocument;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;
import org.wikidata.wdtk.datamodel.interfaces.StatementGroup;
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

	private static final Logger LOG              = LoggerFactory.getLogger(WikidataDswarmImporter.class);

	public static final  String LANGUAGE_CODE_EN = "en";

	private final AtomicLong resourceCount = new AtomicLong();

	private static final Map<String, ItemIdValue>     gdmResourceURIWikidataItemMap     = new HashMap<>();
	private static final Map<String, PropertyIdValue> gdmPropertyURIWikidataPropertyMap = new HashMap<>();

	public void importGDMModel(final String filePath) throws IOException {

		final Observable<Resource> gdmModel = getGDMModel(filePath);

		gdmModel.map(resource -> {

			processGDMResource(resource);

			return resource;
		});
	}

	private void processGDMResource(final Resource resource) {

		resourceCount.incrementAndGet();

		final List<MonolingualTextValue> labels = generateLabels(resource);

		final List<org.wikidata.wdtk.datamodel.interfaces.Statement> wikidataStatements = new ArrayList<>();

		final Set<Statement> gdmStatements = resource.getStatements();

		if (gdmStatements != null) {

			// write statements (if available)

			for (final Statement gdmStatement : gdmStatements) {

				final Optional<org.wikidata.wdtk.datamodel.interfaces.Statement> optionalWikidataStmt = processGDMStatement(gdmStatement);

				if (!optionalWikidataStmt.isPresent()) {

					// TODO: log non-created statements

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

		// TODO: process qualified attributes at GDM statement

		final Claim claim = Datamodel.makeClaim(null, snak, null);

		// note: empty string for statement id (this should be utilised for statements that are created)
		return Optional.ofNullable(Datamodel.makeStatement(claim, null, null, ""));
	}

	private PropertyIdValue processGDMPredicate(final Predicate predicate) {

		final String predicateURI = predicate.getUri();

		return gdmPropertyURIWikidataPropertyMap.computeIfAbsent(predicateURI, predicateURI1 -> {

			List<MonolingualTextValue> labels = generateLabels(predicateURI1);

			// TODO: add datatype (?) - e.g. all literals are strings and all resources are ?
			final PropertyDocument wikidataProperty = Datamodel.makePropertyDocument(null, labels, null, null, null);

			// TODO: create Property at Wikibase (to have a generated Property identifier)

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

	private ItemIdValue processGDMResourceNode(final ResourceNode resourceNode) {

		final String resourceURI = resourceNode.getUri();

		return gdmResourceURIWikidataItemMap.computeIfAbsent(resourceURI, resourceURI1 -> {

			final List<MonolingualTextValue> labels = generateLabels(resourceURI);

			final ItemDocument wikidataItem = Datamodel.makeItemDocument(null, labels, null, null, null, null);

			// TODO: create Item at Wikibase (to have a generated Item identifier)

			return wikidataItem.getItemId();
		});
	}

	private static Observable<Resource> getGDMModel(final String filePath) throws IOException {

		final InputStream gdmModelStream = getGDMModelStream(filePath);

		final ModelParser modelParser = new ModelParser(gdmModelStream);

		return modelParser.parse();
	}

	private static InputStream getGDMModelStream(final String filePath) throws IOException {

		final Path path = Paths.get(filePath);

		return Files.newInputStream(path);
	}

	private List<MonolingualTextValue> generateLabels(final String sourceLabel) {

		final List<MonolingualTextValue> labels = new ArrayList<>();
		final MonolingualTextValue label = Datamodel.makeMonolingualTextValue(sourceLabel, LANGUAGE_CODE_EN);

		labels.add(label);

		return labels;
	}
}
