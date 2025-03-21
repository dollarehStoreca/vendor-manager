package ca.dollareh.pim.integration;

import ca.dollareh.pim.model.Product;
import ca.dollareh.pim.source.ProductSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.net.URIBuilder;
import org.jsoup.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Shopify {

    final Logger logger = LoggerFactory.getLogger(Shopify.class);

    private final String baseUrl;

    private final Path exportPath;

    private final ObjectMapper objectMapper;

    private final ProductSource productSource;

    private final File collectionMappingsFile;
    private final Properties collectionMappings;

    private final String defaultCollectionId;

    public Shopify(ProductSource productSource) {
        this.productSource = productSource;

        baseUrl = System.getenv("SHOPIFY_BASE_URL");

        exportPath = Path.of("workspace/export/" + getClass().getSimpleName() + "/" + productSource.getClass().getSimpleName());

        exportPath.toFile().mkdirs();

        objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        collectionMappingsFile = new File(exportPath.toFile(), "collection.properties");
        collectionMappings = new Properties();

        File rootColPropsFile = new File(collectionMappingsFile.getParentFile().getParentFile(), collectionMappingsFile.getName());
        if (rootColPropsFile.exists()) {
            Properties cProperties = new Properties();
            try {
                cProperties.load(new FileReader(rootColPropsFile));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            defaultCollectionId = (String) cProperties.get(productSource.getClass().getSimpleName());

        } else {
            defaultCollectionId = null;
        }

        if(collectionMappingsFile.exists()) {
            try {
                collectionMappings.load(new FileInputStream(collectionMappingsFile));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public Map<String, Object> createCollection(String title,List<String> downSteamPaths) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        Map<String, Object> collectionMap = new HashMap();
        collectionMap.put("title", title);

        if( downSteamPaths != null && !downSteamPaths.isEmpty()) {
            Map<String, Object> metaFoiledsMap = new HashMap();
            metaFoiledsMap.put("key", "downstream_collection_paths");
            metaFoiledsMap.put("type", "list.single_line_text_field");
            metaFoiledsMap.put("namespace", "vendor");
            metaFoiledsMap.put("value", objectMapper.writeValueAsString(downSteamPaths));

            collectionMap.put("metafields", List.of(metaFoiledsMap));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/custom_collections.json"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper
                        .writeValueAsString(Map.of("custom_collection", collectionMap))))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return objectMapper.readValue(response.body()
                , new TypeReference<>() {
                });
    }

    public void createCollectionMappings() {
        Map<Long, Map<String, Object>> collectionsMap =  getShopifyCollection();


        collectionsMap.entrySet().stream().forEach(longMapEntry -> {
            List<Map<String, Object>> metaFields = getMetafields(longMapEntry.getKey());
            if(!metaFields.isEmpty()) {
                metaFields.stream()
                        .filter(metaFieldMap -> metaFieldMap.get("key").equals("downstream_collection_paths"))
                        .forEach(metaFieldMap -> {
                            List<String> paths = null;
                            try {
                                paths = objectMapper.readerForListOf(String.class).readValue(metaFieldMap.get("value").toString());
                                if(!paths.isEmpty()) {
                                    paths.forEach(s -> {
                                        collectionMappings.put(s, longMapEntry.getKey().toString());
                                    });
                                }
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }

                        });
            }

        });

        try {
            collectionMappings.store(new FileOutputStream(collectionMappingsFile),"Updated for Product Induction");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public void export() throws IOException {

        Path enrichmentPath = Path.of("workspace/enrichment/" + productSource.getClass().getSimpleName());

        List<File> enrichedJsonFiles = List.of(enrichmentPath.toFile().listFiles((dir, name) -> name.endsWith(".json")));


        enrichedJsonFiles.stream().forEach(enrichedJsonFile -> {
            logger.info("Creating Product " + enrichedJsonFile);
            syncProduct(enrichedJsonFile);
        });




    }

    private void syncProduct(File enrichedJsonFile) {
        try {

            Product enrichedProduct = objectMapper
                    .readValue(enrichedJsonFile, Product.class);

            Map<String, Object> shopifyProduct = getShopifyProduct(enrichedProduct);

            File[] jsonFiles = exportPath.toFile().listFiles((dir, name) -> name.startsWith(enrichedProduct.code()));


            File shopifyProductFile = null;

            if (jsonFiles != null && jsonFiles.length == 1) {

                shopifyProductFile = jsonFiles[0];

                JsonNode existingShoppifyProduct = objectMapper.readTree(shopifyProductFile);

                JsonNode newShoppifyProduct = objectMapper.readTree(objectMapper.writeValueAsString(shopifyProduct));

                if (!existingShoppifyProduct.equals(newShoppifyProduct)) {
                    String shopifyProductId = shopifyProductFile.getName()
                            .replaceFirst(enrichedProduct.code()+"-","")
                            .replace(".json","");
                    update(shopifyProductId, shopifyProduct);
                }
            } else {

                Map<String, Object> createdProduct = create(shopifyProduct);

                if (createdProduct.get("product") != null ) {
                    Long id = (Long) ((Map<String, Object>) createdProduct.get("product")).get("id");

                    if(id == null) {
                        logger.error("Unable to create product : " + enrichedProduct.code());
                    } else {
                        shopifyProductFile = new File(exportPath.toFile(), enrichedProduct.code() + "-" + id + ".json");

                        if(defaultCollectionId != null) {
                            associateCollection(id, defaultCollectionId);
                        }

                        createImages(id, enrichedProduct);
                    }
                } else {
                    logger.error("Unable to create product : " + createdProduct);
                }


                List<List<String>> originalCategories = productSource.getCollection(enrichedProduct.code());

                // There are Categories associated with the product.
                // We need to find respective Collection IDs
                if (!originalCategories.isEmpty()){
//                        originalCategories.forEach(categoryList -> {
//                            do {
//                                String path = productSource.getClass().getSimpleName() +
//                                        "-" +
//                                        categoryList.stream().collect(Collectors.joining("-"));
//                                String collectionId = (String) collectionMappings.get(path);
//                                //logger.info(collectionId);
//                                if(collectionId == null){
//                                    logger.info(path + " not found");
//                                } else {
//                                    try {
//                                        associateCollection(id, collectionId);
//                                    } catch (IOException | InterruptedException e) {
//                                        throw new RuntimeException(e);
//                                    }
//                                }
//                                List<String> allExceptLast = new ArrayList<>();
//                                for (int j = 0; j < categoryList.size() -1; j++) {
//                                    allExceptLast.add(categoryList.get(j));
//                                }
//                                categoryList = allExceptLast;
//                            }while(!categoryList.isEmpty());
//
//
//                        });
                }
            }

            if(shopifyProductFile == null) {
                logger.error("Unable to Create Product for " + shopifyProductFile);
            } else {
                objectMapper.writeValue(shopifyProductFile, shopifyProduct);
            }


        } catch (IOException | InterruptedException e) {
            logger.info("Unable to Create Image for " + enrichedJsonFile);
        }
    }

    public Map<String, Object> getShopifyProduct(final Product product) {
        Map<String, Object> shopifyProduct = new HashMap<>(1);

        Map<String, Object> variantMap
                = Map.of("price", product.price(),
                "compare_at_price", product.discount(),
                "inventory_quantity", product.inventryQuantity(),
                "title", "Default Title",
                "inventory_policy", "deny" ,
                "inventory_management","shopify",
                "option1" ,"Default Title",
                "fulfillment_service", "manual",
                "taxable", true,
                "requires_shipping", true);

        Map<String, Object> productMap
                = Map.of("title", product.title(),
                "body_html", product.description(),
                "handle", product.code(),
                "vendor" , "Dollareh",
                "tags" , "auto-imported",
                "variants", List.of(variantMap));

        shopifyProduct.put("product", productMap);

        return shopifyProduct;
    }

    public Map<String, Object> associateCollection(final Long productId, String collectionId) throws IOException, InterruptedException {

        HttpClient client = HttpClient.newHttpClient();

        Map<String, Object> shopifyCollection = new HashMap<>(2);

        shopifyCollection.put("product_id", productId);


        if (collectionId != null) {
            shopifyCollection.put("collection_id",collectionId);
        }

        ;

        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/collects.json"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("collect", shopifyCollection))))
                .build();

        // Send request and get response
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return objectMapper.readValue(response.body()
                , new TypeReference<>() {
                });

    }

    public void createImages(final Long productId, Product product) {

        for (String imageUrl: product.imageUrls()) {
            try {
                File imageFile = productSource.getAssetFile(imageUrl);
                createImage(productId, imageFile.toPath());
            }
            catch (UncheckedIOException |
                   IOException | InterruptedException e) {
                logger.info("Unable to Upload Image for " + productId);
            }
        }



    }


    public Map<String, Object> create(Map<String, Object> shopifyProduct) throws IOException, InterruptedException {

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/products.json"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(shopifyProduct)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return objectMapper.readValue(response.body()
                , new TypeReference<>() {
                });
    }

    public Long getShopifyCollection(String title) throws URISyntaxException {
        HttpClient client = HttpClient.newHttpClient();
        Long collectionId = null;

        URI uri = new URIBuilder(baseUrl + "/custom_collections.json")
                .addParameter("title", title)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri.toString()))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch Shopify collections: " + response.body());
            }

            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, List<Map<String, Object>>> responseBody = objectMapper.readValue(response.body(),
                    new TypeReference<>() {});

            Map<Long, Map<String, Object>> collectionsMap = new HashMap<>();
            List<Map<String, Object>> collections = responseBody.get("custom_collections");

            if (collections != null) {
                for (Map<String, Object> collection : collections) {
                    collectionId = ((Number) collection.get("id")).longValue();
                }
            }

            return collectionId;
        } catch (Exception e) {
            throw new RuntimeException("Error fetching Shopify collections", e);
        }
    }

    /**
     * Gets all the shopify collections as Map.
     * Key os the Map is Collection Id
     * Value of the Map is Collection Object as Map<String, Object>
     * @return collectionsMap
     */
    public Map<Long, Map<String, Object>> getShopifyCollection() {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl  + "/custom_collections.json?limit=250"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch Shopify collections: " + response.body());
            }

            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, List<Map<String, Object>>> responseBody = objectMapper.readValue(response.body(),
                    new TypeReference<>() {});

            Map<Long, Map<String, Object>> collectionsMap = new HashMap<>();
            List<Map<String, Object>> collections = responseBody.get("custom_collections");

            if (collections != null) {
                for (Map<String, Object> collection : collections) {
                    Long id = ((Number) collection.get("id")).longValue();
                    collectionsMap.put(id, collection);
                }
            }

            return collectionsMap;
        } catch (Exception e) {
            throw new RuntimeException("Error fetching Shopify collections", e);
        }
    }

    private List<Map<String, Object>> getMetafields(Long collectionId) {
        HttpClient client = HttpClient.newHttpClient();

        String metafieldsUrl = baseUrl + "/collections/" + collectionId + "/metafields.json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(metafieldsUrl))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch metafields for collection " + collectionId);
            }

            Map<String, List<Map<String, Object>>> responseBody = objectMapper.readValue(response.body(),
                    new TypeReference<>() {
                    });

            return responseBody.getOrDefault("metafields", Collections.emptyList());
        } catch (Exception e) {
            throw new RuntimeException("Error fetching metafields for collection " + collectionId, e);
        }
    }


    public Map<String, Object> update(String productId, Map<String, Object> shopifyProduct) throws IOException, InterruptedException {

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/products/" + productId + ".json"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(shopifyProduct)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return objectMapper.readValue(response.body()
                , new TypeReference<>() {
                });
    }

    private void createImage(Long productId, Path productImage) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        // Convert Image to Base64
        String base64Image = Base64.getEncoder().encodeToString(Files.readAllBytes(productImage));

        // Create JSON request body
        String requestBody = """
        {
          "image": {
            "attachment": "%s"
          }
        }
        """.formatted(base64Image);

        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/products/" + productId + "/images.json"))
                .header("X-Shopify-Access-Token", System.getenv("SHOPIFY_ACCESS_TOKEN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // Send request and get response
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
