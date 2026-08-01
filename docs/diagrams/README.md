# Diagrams

Mermaid in Markdown, not images. Diagrams drift from the system they describe, and the only defence
is making them cheap enough to edit in the same commit as the change — a PNG in a repo is a picture
of what was true once.

| Diagram | What it answers |
| --- | --- |
| [system-context.md](system-context.md) | Who talks to this platform, and what it talks to |
| [containers.md](containers.md) | Which service owns what, and who calls whom |
| [payment-sequence.md](payment-sequence.md) | What actually happens when a payment is created |
| [callback-sequence.md](callback-sequence.md) | How an acquirer's answer becomes a captured payment |
| [state-machine.md](state-machine.md) | The payment lifecycle, and the two things it deliberately excludes |
| [data-model.md](data-model.md) | Every table, grouped by the service that owns it |
| [event-flow.md](event-flow.md) | Every Kafka topic, its producer, and its consumers |
| [deployment.md](deployment.md) | What runs where, in Compose and in Kubernetes |
