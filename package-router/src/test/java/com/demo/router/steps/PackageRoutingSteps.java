package com.demo.router.steps;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Map;

/**
 * Step-definition skeletons for {@code package-routing.feature}.
 *
 * <p><strong>Not executed in CI.</strong> The skeleton is included so
 * the layering pattern is visible:
 * <ol>
 *   <li>DMN = the business rule</li>
 *   <li>SCESIM = the PO unit test</li>
 *   <li>FEATURE = the dev integration test</li>
 * </ol>
 *
 * <p>A real implementation would:
 * <ul>
 *   <li>Publish a JSON message to the in-cluster Kafka bootstrap at
 *       {@code kafka:9092} — use a lightweight {@code KafkaTemplate}
 *       or a {@code org.testcontainers} broker for local runs.</li>
 *   <li>Poll {@code http://package-router:8080/actuator/prometheus}
 *       for the expected counter increment — the Serenity REST helper
 *       is ideal here.</li>
 *   <li>Open a {@code MongoClient} connected to MongoDB at
 *       {@code mongodb:27017} and assert on the {@code audit}
 *       collection.</li>
 * </ul>
 */
public class PackageRoutingSteps {

    private String publishedCorrelationId;

    @Given("a document published to {string} with:")
    public void aDocumentPublishedWith(String topic, DataTable fields) {
        // TODO: publish a serialized EnrichedDocument to <topic> using
        // a KafkaTemplate and store its correlationId for downstream
        // assertions. The demo skeleton is intentionally unimplemented.
        Map<String, String> row = fields.asMap(String.class, String.class);
        this.publishedCorrelationId = "bdd-" + System.currentTimeMillis();
        throw new UnsupportedOperationException(
                "See the README in src/test/ — this step is a layered-testing demonstration, "
                        + "not a runnable test. Fields: " + row + " on " + topic);
    }

    @When("the package-router processes the message")
    public void thePackageRouterProcesses() {
        // TODO: wait for the package.routed counter to tick or poll
        // /recent endpoint until the correlationId appears.
    }

    @Then("a message should appear on {string} within {int} seconds")
    public void aMessageShouldAppearOn(String topic, int seconds) {
        // TODO: attach a consumer to the topic and assert the
        // correlationId appears within the budget.
    }

    @And("the Micrometer counter {string} should have exactly {int} increment with tags:")
    public void theMicrometerCounterShouldTick(String metric, int count, DataTable tags) {
        // TODO: scrape /actuator/prometheus and match the exact tagged
        // series.
    }

    @And("an audit record should appear in MongoDB collection {string} with:")
    public void anAuditRecordShouldAppear(String collection, DataTable fields) {
        // TODO: open a MongoClient and query the collection for a
        // record matching this correlationId + field expectations.
    }
}
