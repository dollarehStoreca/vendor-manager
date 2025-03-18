package ca.dollareh.pim.source;

import ca.dollareh.pim.model.Product;
import ca.dollareh.pim.util.HttpUtil;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Consumer;

public class MultiCraft extends ProductSource {

    public static final String BASE_URL = "https://multicraft.ca";

    private final Connection session;

    MultiCraft(final Consumer<Product> newProductConsumer,
               final Consumer<Product> modifiedProductConsumer) {
        super(newProductConsumer, modifiedProductConsumer);
        session = Jsoup.newSession()
                .timeout(45 * 1000)
                .maxBodySize(5 * 1024 * 1024);
    }

    @Override
    protected void login() throws IOException {
        logger.info("Logging into Multicraft");
    }

    @Override
    protected void logout() throws IOException {
        logger.info("Logged out from Multicraft");
    }

    @Override
    protected void browse() throws IOException {
        logger.info("Browsing Multicraft");
        Document document = getHTMLDocument("/en/brand/");

        Element ulElement = document.selectFirst("ul.brandsList");
        if(ulElement != null) {
            Elements liElements = ulElement.children();

            liElements.stream().parallel().forEach(liElement -> {
                try {
                    browse(List.of(HttpUtil.getRequestParameter(liElement.selectFirst("a").attr("href"), "code")));
                } catch (IOException | URISyntaxException e) {
                    logger.error("Browsing Failed", e);
                }
            });
        }
    }

    @Override
    protected File downloadAsset(final String assetUrl) throws IOException {
        return null;
    }

    private void browse(final List<String> categories) throws IOException {
        logger.info("Browsing brand {}", categories);
        Document subBrandDocument = getHTMLDocument("/en/brand/subbrands?code=" + categories.getLast());

        Elements skusEls = subBrandDocument.select("#skusCards>li");

        skusEls.stream().parallel().forEach(skusEl -> {
            String productCode = skusEl.selectFirst(".summary-id").text().trim();

            logger.info("Product Found {}", productCode);
        });
    }

    private Document getHTMLDocument(final String url) throws IOException {
        return session.newRequest(BASE_URL + url).get();
    }
}
