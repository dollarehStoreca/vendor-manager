package ca.dollareh.vendor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ca.dollareh.core.model.Product;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import ca.dollareh.core.model.Category;

public class MultiCraft {

    private static String BASE_URL = "https://multicraft.ca";

    private final String cokkie;

    public MultiCraft() {
        cokkie = System.getenv("DH_MULTICRAFT_COKKIE");
    }

    /**
     * Get All Categories from Multicraft.
     * 
     * @return categories
     * @throws IOException
     */
    public List<Category> getCategories() throws URISyntaxException {
        return getCategories(null, getHTMLDocument("/en/brand"));
    }

    /**
     * Get All Categories from Multicraft.
     * @param code
     * @return categories
     * @throws IOException
     */
    public Category getCategory(final Category parent,String code) throws URISyntaxException {
        return getCategory(parent, code, getHTMLDocument("/en/brand/subbrands?code=" + code));
    }

    private Category getCategory(final Category parent,
                                final String code,
                                 final Document doc) throws URISyntaxException {

        Category category = new Category(parent, code,new ArrayList<>() , new ArrayList<>());

        List<Category> categories = getCategories(category, doc);
        category.categories().addAll(categories);

        List<Product> products = getProducts(category, doc);
        category.products().addAll(products);

        return category;
    }

    private List<Category> getCategories(final Category parent,final Document doc) throws URISyntaxException {
        List<Category> categories = new ArrayList<>();

        Elements brandsEls = doc.select("ul.brandsList>li>a");

        for (Element brandsAnchorEl : brandsEls) {
            Optional<String> codeOp = new URIBuilder(brandsAnchorEl.attr("href"))
                    .getQueryParams()
                    .stream()
                    .filter(nameValuePair -> nameValuePair.getName().equals("code"))
                    .findFirst()
                    .map(NameValuePair::getValue);

            if (codeOp.isPresent()) {
                categories.add(getCategory(parent, codeOp.get()));
            }
        }
        return categories;
    }

    /**
     * Get All Products from Multicraft.
     * @param category
     * @param brandDoc
     * @return categories
     * @throws IOException
     */
    public List<Product> getProducts(Category category,
                                     Document brandDoc) throws URISyntaxException {
        List<Product> products = new ArrayList<>();

        Elements skusEls = brandDoc.select("#skusCards>li");

        for (Element skusEl : skusEls) {
            products.add(getProduct(category, skusEl.selectFirst(".summary-id").text()));
        }

        return products;
    }

    /**
     * Get Product from Multicraft.
     * @param category
     * @param productCode
     * @return Product
     * @throws IOException
     */
    public Product getProduct(final Category category,
                              final String productCode) {

        Document doc = getHTMLDocument("/en/brand/sku?id=" + productCode);

        Elements imageEls = doc.select("#img-previews>li>img");

        String[] imageUrls = new String[imageEls.size()];

        for (int i = 0; i < imageEls.size(); i++) {
            imageUrls[i] = BASE_URL + imageEls.get(i).attr("src").split("\\?")[0];
        }

        Elements fieldEls = doc.select("div.details-brief > .row");

        float price = 0;
        float discount = 0;

        for (Element fieldEl : fieldEls) {
            if(fieldEl.selectFirst(".hdr").text().equals("unit price")) {

                String priceText = fieldEl
                        .selectFirst(".vlu")
                        .text()
                        .replace("$","");
                if(priceText.contains(" ")) {
                    String[] spli = priceText.split(" ");
                    price = Float.parseFloat(spli[0]);
                    discount = Float.parseFloat(spli[1]);
                } else {
                    price = Float.parseFloat(priceText);
                }

            }
        }

        return new Product(category,
                productCode
                , doc.selectFirst(".details-desc") == null ? null : doc.selectFirst(".details-desc").text()
                , doc.selectFirst(".details-blurb") == null ? null : doc.selectFirst(".details-blurb").text()
                , price
                , discount
                , imageUrls
        );
    }

    private Document getHTMLDocument(final String url) {
        // Define the cURL command (Example: Fetching Google's homepage)
        String[] command = {
                "curl" , BASE_URL + url
                ,"--compressed"
                ,"-H", "User-Agent: Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"
                ,"-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                ,"-H", "Accept-Language: en-US,en;q=0.5"
                ,"-H", "Accept-Encoding: gzip, deflate, br, zstd"
                ,"-H", "Connection: keep-alive"
                ,"-H", "Cookie: " + cokkie
                ,"-H", "Upgrade-Insecure-Requests: 1"
                ,"-H", "Sec-Fetch-Dest: document"
                ,"-H", "Sec-Fetch-Mode: navigate"
                ,"-H", "Sec-Fetch-Site: none"
                ,"-H", "Sec-Fetch-User: ?1"
                ,"-H", "Priority: u=1"
        };

        StringBuilder builder = new StringBuilder();

        try {
            // Start the process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true); // Merge stdout and stderr

            Process process = processBuilder.start();

            boolean startAppending = false ;

            // Read the output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {

                    if(startAppending) {
                        builder.append(line);
                    } else {
                        if(line.trim().equals("<!DOCTYPE html>")) {
                            startAppending = true;
                        }
                    }

                }
            }

            // Wait for the process to complete
            int exitCode = process.waitFor();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return Jsoup.parse(builder.toString());
    }

}
