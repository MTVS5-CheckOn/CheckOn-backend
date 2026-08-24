# 위험 탐지 계약 파일

- `risk-detection-kafka.asyncapi.yaml`: Backend 내부 위험 탐지 Kafka 메시지 계약의 정본입니다. AI 서버는 이 계약을 구현하지 않고 `POST /v1/detect` HTTP OpenAPI만 구현합니다.
- `risk-detection-control.openapi.yaml`: 교사 화면이 위험 탐지를 접수하고 상태를 조회하는 REST API입니다. Apidog 가져오기에는 이 OpenAPI 3.1 파일을 사용합니다.

Apidog의 현재 가져오기 문서는 OpenAPI 3.0/3.1 및 Swagger 형식을 안내하며 AsyncAPI 가져오기를 명시하지 않습니다. 따라서 Kafka 계약은 AsyncAPI 파일로 보관하고, Apidog에는 REST 제어 API OpenAPI를 올리는 구성이 안전합니다. [Apidog OpenAPI import 문서](https://docs.apidog.com/en/import-openapi-spec-635046m0)를 참고하세요.
