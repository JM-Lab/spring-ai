/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vectorstore.azure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.HnswAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.HnswParameters;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchAlgorithmMetric;
import com.azure.search.documents.indexes.models.VectorSearchProfile;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.IndexingResult;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.VectorSearchOptions;
import com.azure.search.documents.models.VectorizedQuery;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.model.EmbeddingUtils;
import org.springframework.ai.observation.conventions.VectorStoreProvider;
import org.springframework.ai.observation.conventions.VectorStoreSimilarityMetric;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext.Builder;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Uses Azure Cognitive Search as a backing vector store. Documents can be preloaded into
 * a Cognitive Search index and managed via Azure tools or added and managed through this
 * VectorStore. The underlying index is configured in the provided Azure
 * SearchIndexClient.
 *
 * @author Greg Meyer
 * @author Xiangyang Yu
 * @author Christian Tzolov
 * @author Josh Long
 * @author Thomas Vitale
 * @author Soby Chacko
 */
public class AzureVectorStore extends AbstractObservationVectorStore implements InitializingBean {

	public static final String DEFAULT_INDEX_NAME = "spring_ai_azure_vector_store";

	private static final Logger logger = LoggerFactory.getLogger(AzureVectorStore.class);

	private static final String SPRING_AI_VECTOR_CONFIG = "spring-ai-vector-config";

	private static final String SPRING_AI_VECTOR_PROFILE = "spring-ai-vector-profile";

	private static final String ID_FIELD_NAME = "id";

	private static final String CONTENT_FIELD_NAME = "content";

	private static final String EMBEDDING_FIELD_NAME = "embedding";

	private static final String METADATA_FIELD_NAME = "metadata";

	private static final int DEFAULT_TOP_K = 4;

	private static final Double DEFAULT_SIMILARITY_THRESHOLD = 0.0;

	private static final String METADATA_FIELD_PREFIX = "meta_";

	private final SearchIndexClient searchIndexClient;

	private final EmbeddingModel embeddingModel;

	private final FilterExpressionConverter filterExpressionConverter;

	private final boolean initializeSchema;

	private final BatchingStrategy batchingStrategy;

	/**
	 * List of metadata fields (as field name and type) that can be used in similarity
	 * search query filter expressions. The {@link Document#getMetadata()} can contain
	 * arbitrary number of metadata entries, but only the fields listed here can be used
	 * in the search filter expressions.
	 * <p>
	 * If new entries are added ot the filterMetadataFields the affected documents must be
	 * (re)updated.
	 */
	private final List<MetadataField> filterMetadataFields;

	private SearchClient searchClient;

	private int defaultTopK = DEFAULT_TOP_K;

	private Double defaultSimilarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

	private String indexName = DEFAULT_INDEX_NAME;

	/**
	 * Constructs a new AzureCognitiveSearchVectorStore.
	 * @param searchIndexClient A pre-configured Azure {@link SearchIndexClient} that CRUD
	 * for Azure search indexes and factory for {@link SearchClient}.
	 * @param embeddingModel The client for embedding operations.
	 */
	public AzureVectorStore(SearchIndexClient searchIndexClient, EmbeddingModel embeddingModel,
			boolean initializeSchema) {
		this(searchIndexClient, embeddingModel, initializeSchema, List.of());
	}

	/**
	 * Constructs a new AzureCognitiveSearchVectorStore.
	 * @param searchIndexClient A pre-configured Azure {@link SearchIndexClient} that CRUD
	 * for Azure search indexes and factory for {@link SearchClient}.
	 * @param embeddingModel The client for embedding operations.
	 * @param filterMetadataFields List of metadata fields (as field name and type) that
	 * can be used in similarity search query filter expressions.
	 */
	public AzureVectorStore(SearchIndexClient searchIndexClient, EmbeddingModel embeddingModel,
			boolean initializeSchema, List<MetadataField> filterMetadataFields) {
		this(searchIndexClient, embeddingModel, initializeSchema, filterMetadataFields, ObservationRegistry.NOOP, null,
				new TokenCountBatchingStrategy());
	}

	/**
	 * Constructs a new AzureCognitiveSearchVectorStore.
	 * @param searchIndexClient A pre-configured Azure {@link SearchIndexClient} that CRUD
	 * for Azure search indexes and factory for {@link SearchClient}.
	 * @param embeddingModel The client for embedding operations.
	 * @param filterMetadataFields List of metadata fields (as field name and type) that
	 * can be used in similarity search query filter expressions.
	 * @param observationRegistry The observation registry to use.
	 * @param customObservationConvention The optional, custom search observation
	 * convention to use.
	 */
	public AzureVectorStore(SearchIndexClient searchIndexClient, EmbeddingModel embeddingModel,
			boolean initializeSchema, List<MetadataField> filterMetadataFields, ObservationRegistry observationRegistry,
			VectorStoreObservationConvention customObservationConvention, BatchingStrategy batchingStrategy) {

		super(observationRegistry, customObservationConvention);

		Assert.notNull(embeddingModel, "The embedding model can not be null.");
		Assert.notNull(searchIndexClient, "The search index client can not be null.");
		Assert.notNull(filterMetadataFields, "The filterMetadataFields can not be null.");

		this.initializeSchema = initializeSchema;
		this.searchIndexClient = searchIndexClient;
		this.embeddingModel = embeddingModel;
		this.filterMetadataFields = filterMetadataFields;
		this.filterExpressionConverter = new AzureAiSearchFilterExpressionConverter(filterMetadataFields);
		this.batchingStrategy = batchingStrategy;
	}

	/**
	 * Change the Index Name.
	 * @param indexName The Azure VectorStore index name to use.
	 */
	public void setIndexName(String indexName) {
		Assert.hasText(indexName, "The index name can not be empty.");
		this.indexName = indexName;
	}

	/**
	 * Sets the a default maximum number of similar documents returned.
	 * @param topK The default maximum number of similar documents returned.
	 */
	public void setDefaultTopK(int topK) {
		Assert.isTrue(topK >= 0, "The topK should be positive value.");
		this.defaultTopK = topK;
	}

	/**
	 * Sets the a default similarity threshold for returned documents.
	 * @param similarityThreshold The a default similarity threshold for returned
	 * documents.
	 */
	public void setDefaultSimilarityThreshold(Double similarityThreshold) {
		Assert.isTrue(similarityThreshold >= 0.0 && similarityThreshold <= 1.0,
				"The similarity threshold must be in range [0.0:1.00].");
		this.defaultSimilarityThreshold = similarityThreshold;
	}

	@Override
	public void doAdd(List<Document> documents) {

		Assert.notNull(documents, "The document list should not be null.");
		if (CollectionUtils.isEmpty(documents)) {
			return; // nothing to do;
		}

		List<float[]> embeddings = this.embeddingModel.embed(documents, EmbeddingOptionsBuilder.builder().build(),
				this.batchingStrategy);

		final var searchDocuments = documents.stream().map(document -> {
			SearchDocument searchDocument = new SearchDocument();
			searchDocument.put(ID_FIELD_NAME, document.getId());
			searchDocument.put(EMBEDDING_FIELD_NAME, embeddings.get(documents.indexOf(document)));
			searchDocument.put(CONTENT_FIELD_NAME, document.getContent());
			searchDocument.put(METADATA_FIELD_NAME, new JSONObject(document.getMetadata()).toJSONString());

			// Add the filterable metadata fields as top level fields, allowing filler
			// expressions on them.
			for (MetadataField mf : this.filterMetadataFields) {
				if (document.getMetadata().containsKey(mf.name())) {
					searchDocument.put(METADATA_FIELD_PREFIX + mf.name(), document.getMetadata().get(mf.name()));
				}
			}

			return searchDocument;
		}).toList();

		IndexDocumentsResult result = this.searchClient.uploadDocuments(searchDocuments);

		for (IndexingResult indexingResult : result.getResults()) {
			Assert.isTrue(indexingResult.isSucceeded(),
					String.format("Document with key %s did not upload successfully", indexingResult.getKey()));
		}
	}

	@Override
	public Optional<Boolean> doDelete(List<String> documentIds) {

		Assert.notNull(documentIds, "The document ID list should not be null.");
		if (CollectionUtils.isEmpty(documentIds)) {
			return Optional.of(true); // nothing to do;
		}

		final var searchDocumentIds = documentIds.stream().map(documentId -> {
			SearchDocument searchDocument = new SearchDocument();
			searchDocument.put(ID_FIELD_NAME, documentId);
			return searchDocument;
		}).toList();

		var results = this.searchClient.deleteDocuments(searchDocumentIds);

		boolean resSuccess = true;

		for (IndexingResult result : results.getResults()) {
			if (!result.isSucceeded()) {
				resSuccess = false;
				break;
			}
		}

		return Optional.of(resSuccess);
	}

	@Override
	public List<Document> similaritySearch(String query) {
		return this.similaritySearch(SearchRequest.query(query)
			.withTopK(this.defaultTopK)
			.withSimilarityThreshold(this.defaultSimilarityThreshold));
	}

	@Override
	public List<Document> doSimilaritySearch(SearchRequest request) {

		Assert.notNull(request, "The search request must not be null.");

		var searchEmbedding = this.embeddingModel.embed(request.getQuery());

		final var vectorQuery = new VectorizedQuery(EmbeddingUtils.toList(searchEmbedding))
			.setKNearestNeighborsCount(request.getTopK())
			// Set the fields to compare the vector against. This is a comma-delimited
			// list of field names.
			.setFields(EMBEDDING_FIELD_NAME);

		var searchOptions = new SearchOptions()
			.setVectorSearchOptions(new VectorSearchOptions().setQueries(vectorQuery));

		if (request.hasFilterExpression()) {
			String oDataFilter = this.filterExpressionConverter.convertExpression(request.getFilterExpression());
			searchOptions.setFilter(oDataFilter);
		}

		final var searchResults = this.searchClient.search(null, searchOptions, Context.NONE);

		return searchResults.stream()
			.filter(result -> result.getScore() >= request.getSimilarityThreshold())
			.map(result -> {

				final AzureSearchDocument entry = result.getDocument(AzureSearchDocument.class);

				Map<String, Object> metadata = (StringUtils.hasText(entry.metadata()))
						? JSONObject.parseObject(entry.metadata(), new TypeReference<Map<String, Object>>() {

						}) : Map.of();

				metadata.put(DocumentMetadata.DISTANCE.value(), 1.0 - result.getScore());

				return Document.builder()
					.id(entry.id())
					.content(entry.content)
					.metadata(metadata)
					.score(result.getScore())
					.build();
			})
			.collect(Collectors.toList());
	}

	@Override
	public void afterPropertiesSet() throws Exception {

		if (!this.initializeSchema) {
			this.searchClient = this.searchIndexClient.getSearchClient(this.indexName);
			return;
		}

		int dimensions = this.embeddingModel.dimensions();

		List<SearchField> fields = new ArrayList<>();

		fields.add(new SearchField(ID_FIELD_NAME, SearchFieldDataType.STRING).setKey(true)
			.setFilterable(true)
			.setSortable(true));
		fields.add(new SearchField(EMBEDDING_FIELD_NAME, SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
			.setSearchable(true)
			.setHidden(false)
			.setVectorSearchDimensions(dimensions)
			// This must match a vector search configuration name.
			.setVectorSearchProfileName(SPRING_AI_VECTOR_PROFILE));
		fields.add(new SearchField(CONTENT_FIELD_NAME, SearchFieldDataType.STRING).setSearchable(true)
			.setFilterable(true));
		fields.add(new SearchField(METADATA_FIELD_NAME, SearchFieldDataType.STRING).setSearchable(true)
			.setFilterable(true));

		for (MetadataField filterableMetadataField : this.filterMetadataFields) {
			fields.add(new SearchField(METADATA_FIELD_PREFIX + filterableMetadataField.name(),
					filterableMetadataField.fieldType())
				.setSearchable(false)
				.setFacetable(true));
		}

		SearchIndex searchIndex = new SearchIndex(this.indexName).setFields(fields)
			// VectorSearch configuration is required for a vector field. The name used
			// for the vector search algorithm configuration must match the configuration
			// used by the search field used for vector search.
			.setVectorSearch(new VectorSearch()
				.setProfiles(Collections
					.singletonList(new VectorSearchProfile(SPRING_AI_VECTOR_PROFILE, SPRING_AI_VECTOR_CONFIG)))
				.setAlgorithms(Collections.singletonList(new HnswAlgorithmConfiguration(SPRING_AI_VECTOR_CONFIG)
					.setParameters(new HnswParameters().setM(4)
						.setEfConstruction(400)
						.setEfSearch(1000)
						.setMetric(VectorSearchAlgorithmMetric.COSINE)))));

		SearchIndex index = this.searchIndexClient.createOrUpdateIndex(searchIndex);

		logger.info("Created search index: " + index.getName());

		this.searchClient = this.searchIndexClient.getSearchClient(this.indexName);
	}

	@Override
	public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {

		return VectorStoreObservationContext.builder(VectorStoreProvider.AZURE.value(), operationName)
			.withCollectionName(this.indexName)
			.withDimensions(this.embeddingModel.dimensions())
			.withSimilarityMetric(this.initializeSchema ? VectorStoreSimilarityMetric.COSINE.value() : null);
	}

	public record MetadataField(String name, SearchFieldDataType fieldType) {

		public static MetadataField text(String name) {
			return new MetadataField(name, SearchFieldDataType.STRING);
		}

		public static MetadataField int32(String name) {
			return new MetadataField(name, SearchFieldDataType.INT32);
		}

		public static MetadataField int64(String name) {
			return new MetadataField(name, SearchFieldDataType.INT64);
		}

		public static MetadataField decimal(String name) {
			return new MetadataField(name, SearchFieldDataType.DOUBLE);
		}

		public static MetadataField bool(String name) {
			return new MetadataField(name, SearchFieldDataType.BOOLEAN);
		}

		public static MetadataField date(String name) {
			return new MetadataField(name, SearchFieldDataType.DATE_TIME_OFFSET);
		}

	}

	/**
	 * Internal data structure for retrieving and storing documents.
	 */
	private record AzureSearchDocument(String id, String content, List<Float> embedding, String metadata) {

	}

}
