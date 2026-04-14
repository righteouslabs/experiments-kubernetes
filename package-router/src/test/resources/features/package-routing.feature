@package-router @integration @sample
Feature: Package router — integration-aware assertion for one HIGH-priority document

  This is a ONE-SAMPLE demonstration of the integration-aware testing
  pattern layered over Kogito's native SCESIM file. The SCESIM file
  proves the DMN rows behave correctly in isolation. This feature
  proves that the wider system — Kafka producer, DMN evaluation,
  Micrometer counters, MongoDB audit — all work together for a single
  representative case.

  The production harness wraps every row of the .scesim into a
  matching feature scenario so the PO's test plan has full integration
  coverage. For the demo we only supply one to keep wiring visible
  without duplicating rows.

  Scenario: HIGH priority with substantial body routes to packages.express
    Given a document published to "documents.enriched" with:
      | priority        | HIGH |
      | bodyLength      | 600  |
      | enrichmentGrade | B    |
    When the package-router processes the message
    Then a message should appear on "packages.express" within 5 seconds
    And the Micrometer counter "package.routed" should have exactly 1 increment with tags:
      | topic           | packages.express                           |
      | routing_reason  | High priority with substantial payload     |
    And an audit record should appear in MongoDB collection "audit" with:
      | serviceName     | package-router |
      | decisionsFired  | [2]            |
