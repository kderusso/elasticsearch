# PII redaction and OCR for session memories

Session memories go through an ingest pipeline before they are stored and embedded. The pipeline extracts text from PDF, image and HTML media and redacts email addresses, IP addresses and phone numbers from the text.

## How it works

1. `remember` indexes the memory into `.context-engine-session-memory` with the `context-engine-session-memory` pipeline set on the request ([SessionMemoryIndexService](src/main/java/org/elasticsearch/xpack/contextengine/memory/SessionMemoryIndexService.java#L76)).

2. The pipeline runs on the coordinating node, before the `inputs` semantic field is embedded ([TransportAbstractBulkAction](../../../server/src/main/java/org/elasticsearch/action/bulk/TransportAbstractBulkAction.java#L291)).

3. `remember` reads the stored document back and returns it, so the response has the extracted and redacted text.

The pipeline has four processors, in this order.

| Processor | What it does |
|---|---|
| [`context_engine_extract`](src/main/java/org/elasticsearch/xpack/contextengine/memory/ingest/ExtractProcessor.java) | Sends the PDF, image and HTML items in `inputs` to a `document_extraction` endpoint and writes the text to `extracted_content`, one block per item. |
| `redact` on `content` | Replaces `%{EMAILADDRESS:EMAIL}`, `%{IP:IP}` and `%{PHONE:PHONE}` matches with `<EMAIL>`, `<IP>` and `<PHONE>`. |
| `redact` on `extracted_content` | Applies the same patterns to the OCR text. |
| [`context_engine_inputs`](src/main/java/org/elasticsearch/xpack/contextengine/memory/ingest/SessionMemoryInputsProcessor.java) | Rewrites `inputs` as `content`, `extracted_content` and the media items, so the redacted text is what gets embedded. |

The plugin ships the first and last processors because no built-in processor calls the `document_extraction` task type or knows the `inputs` layout. `redact` is the x-pack [Grok redact processor](../redact/src/main/java/org/elasticsearch/xpack/redact/RedactProcessor.java). It needs no ML node and no LLM, which is what makes it usable in a pipeline. Grok has no built-in phone pattern, so the pipeline defines `PHONE` for North American numbers with separators, such as `555-123-4567`. A bare run of digits is not treated as a phone number.

[SessionMemoryPipelineRegistry](src/main/java/org/elasticsearch/xpack/contextengine/memory/ingest/SessionMemoryPipelineRegistry.java) builds the pipeline from the node settings and installs it once the cluster has a master. The data stream template also sets `index.default_pipeline`, which covers writes that bypass `remember` once a backing index exists. `remember` still has to name the pipeline on its request: a system data stream's template is a plugin descriptor, not cluster metadata, so the bulk action cannot resolve a default pipeline until the data stream exists ([IngestService](../../../server/src/main/java/org/elasticsearch/ingest/IngestService.java#L1946)).

## Settings

| Setting | Default | Effect |
|---|---|---|
| `xpack.context_engine.session_memory.extraction.enabled` | `true` | Includes the `context_engine_extract` processor. |
| `xpack.context_engine.session_memory.extraction_inference_id` | `.jina-reader` | The `document_extraction` endpoint it calls. |
| `xpack.context_engine.session_memory.redaction.enabled` | `true` | Includes the two `redact` processors. |

Settings are read at startup. The registry only installs the pipeline when it is missing, so a changed setting takes effect after `DELETE _ingest/pipeline/context-engine-session-memory` or on a fresh data directory.

With the defaults, the installed pipeline is:

```
GET _ingest/pipeline/context-engine-session-memory
```

```json
{
  "context-engine-session-memory": {
    "description": "Extracts text from session memory media and redacts PII before the memory is embedded",
    "version": 1,
    "processors": [
      {
        "context_engine_extract": {
          "inference_id": ".jina-reader"
        }
      },
      {
        "redact": {
          "field": "content",
          "patterns": ["%{EMAILADDRESS:EMAIL}", "%{IP:IP}", "%{PHONE:PHONE}"],
          "pattern_definitions": {
            "PHONE": "(?:\\+?\\d{1,2}[-. ])?\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}"
          },
          "ignore_missing": true
        }
      },
      {
        "redact": {
          "field": "extracted_content",
          "patterns": ["%{EMAILADDRESS:EMAIL}", "%{IP:IP}", "%{PHONE:PHONE}"],
          "pattern_definitions": {
            "PHONE": "(?:\\+?\\d{1,2}[-. ])?\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}"
          },
          "ignore_missing": true
        }
      },
      {
        "context_engine_inputs": {}
      }
    ]
  }
}
```

## Limits

- Grok only matches patterns. Names, postal addresses and free-form account numbers are not redacted. The real fix is a NER model such as [`elastic/distilbert-base-uncased-finetuned-conll03-english`](https://huggingface.co/elastic/distilbert-base-uncased-finetuned-conll03-english): import it with Eland onto an ML node, then replace the two `redact` processors with an `inference` processor that runs it over `content` and `extracted_content` and a `script` processor that replaces each returned entity with its class, such as `<PER>` or `<LOC>`.

- The media itself is stored unchanged. Redaction only applies to the text OCR pulls out of it.

- `.jina-reader` does not OCR scanned PDFs. Send scans as `image`.

- The pipeline is editable with `PUT _ingest/pipeline/context-engine-session-memory`, and `_simulate` shows what a document will look like.

## Running it locally

OCR uses `.jina-reader` on EIS, which as of this POC is only available in QA.

Start Elasticsearch with the QA EIS URL:

```bash
./gradlew run -Dtests.es.xpack.inference.elastic.url=https://inference.eu-west-1.aws.svc.qa.elastic.cloud
```

Once the cluster is green, register the Cloud Connect key from a Kibana checkout. The script reads the key from `KIBANA_EIS_CCM_API_KEY` or from Vault; see the [inference CLI README](https://github.com/elastic/kibana/blob/main/x-pack/platform/packages/shared/kbn-inference-cli/README.md#eis) for the Vault login.

```bash
node scripts/eis.js
```

`GET _inference/.jina-reader` should return the endpoint. Remember a memory that contains PII:

```
POST _context_engine/session_memory/_remember
{
  "session_id": "s-1",
  "user_id": "elastic",
  "space": "default",
  "content": "Call me at 555-123-4567 or kathleen@example.com from 10.1.2.3."
}
```

The response and the stored document contain `Call me at <PHONE> or <EMAIL> from <IP>.`

Remember a scanned page. `value` is a data URL, so paste the base64 of a PNG or JPEG that has an email address or phone number on it:

```
POST _context_engine/session_memory/_remember
{
  "session_id": "s-1",
  "user_id": "elastic",
  "space": "default",
  "content": "The receipt from the vendor.",
  "media": [
    { "type": "image", "value": "data:image/png;base64,<base64 of the scanned page>" }
  ]
}
```

The response has the OCR text in `extracted_content`, with the same placeholders where the page had PII. Read both memories back with:

```
GET _context_engine/session_memory?session_id=s-1
```

To turn either step off, or to use another `document_extraction` endpoint:

```bash
./gradlew run -Dtests.es.xpack.context_engine.session_memory.redaction.enabled=false
./gradlew run -Dtests.es.xpack.context_engine.session_memory.extraction.enabled=false
./gradlew run -Dtests.es.xpack.context_engine.session_memory.extraction_inference_id=my-extractor
```
