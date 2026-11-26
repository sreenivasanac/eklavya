# Third-party notices

The screen model and its text form (`screen/`) follow the design of
[OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse) (MIT License,
Copyright (c) its authors): the plain-Kotlin node model, the numbered
one-line-per-element format, taking a button's label from the text inside it,
and the label truncation helper. The code here is written for this project.

The test fixtures under `screen/src/test/resources/24go/` are screen dumps of
the 24GO app with personal details replaced. They are included only to test
this project's code; 24GO and its content belong to 24 Hour Fitness.

## Wake word

- The keyword-spotting model under `app/src/main/assets/kws/` (encoder, decoder, joiner, tokens) is
  `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` from the
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) project (Apache License 2.0), unmodified apart
  from shorter file names. `keywords.txt` is this project's own.
- The app links the sherpa-onnx Android library (Apache License 2.0), which bundles ONNX Runtime (MIT
  License), and OkHttp (Apache License 2.0).
