# Malli schema for AWS Cloudformation templates

This library can generate a [Malli](https://github.com/metosin/malli) schema and use this schema to validate [AWS Cloudformation]([https://aws.amazon.com/cloudformation/) templates. It is meant as a building block for other AWS Cloudformation libraries / tools.

## Validation

```
clj -m adgoji.aws.cloudformation.malli.validation/validate-dir <template-dir>
```

## Generation

```
clj -m adgoji.aws.cloudformation.malli.generation/write-malli "dev-resources/cloudformation.malli.edn"
```

## Licence

Copyright © 2020 Jeroen van Dijk, adgoji

Distributed under the Eclipse Public License 1.0
