# Ledger API architecture

The REST API and RabbitMQ listener share the same transaction posting service. MySQL stores ledger state and the transactional outbox. RabbitMQ delivers settled payments and transaction announcements.

- [Application overview](overview.md)
- [REST endpoints and database access](endpoints.md)
- [RabbitMQ listener and outbox publisher](messaging.md)
- [MySQL schema and repository map](database.md)

GitHub renders the Mermaid diagrams in these Markdown files.

[Original SVG overview](ledger-architecture.svg)
