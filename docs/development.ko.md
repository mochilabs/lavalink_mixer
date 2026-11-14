# 개발 및 테스트 가이드 (Development Guide)

> English version: [development.md](development.md)

이 문서는 `lavalink_mixer`의 로컬 빌드, 변경 범위별 테스트 선택, 캘리브레이션 태스크, 전환 로그 분석 및 버전/API 스키마 관리 절차를 설명합니다.

---

## 로컬 빌드 및 테스트 실행

**JDK 17** 환경에서 저장소 루트로부터 Gradle 래퍼를 실행합니다 (`./gradlew` 내부 Java 17 툴체인 설정 포함).

```bash
# 전체 컴파일, 유닛/통합 테스트, JAR 패키징 검증
./gradlew build

# 특정 테스트 클래스 또는 패턴만 빠르게 실행
./gradlew test --tests 'io.github.mochilabs.mixer.SinglePassAnalysisTest'
./gradlew test --tests 'io.github.mochilabs.mixer.*BeatMatch*'
```

> [!IMPORTANT]
> 코드 변경 후에는 선택 실행 테스트 외의 상호작용까지 검증하기 위해 반드시 `./gradlew build` 전체 통과를 확인하세요. 문서만 수정한 경우에도 문서 내 모든 심볼, 명령어, 경로, Markdown 링크를 저장소와 대조 검증해야 합니다.

---

## 변경 영역별 테스트 매트릭스

통합 테스트는 로컬 합성 WAV 파일을 생성해 실제 Lavaplayer 플레이어 파이프라인을 구동하며, 실행 중인 Discord 봇이 필요하지 않습니다. 일부 테스트는 네이티브 오디오 라이브러리를 로드하므로 환경의 네이티브 로딩 오류와 동작 회귀를 구분해야 합니다.

| 변경 영역 | 우선 실행할 테스트 클래스 |
| --- | --- |
| 분석 소유권, 단일 오픈, 재시도 정책 | `SinglePassAnalysisTest`, `AnalysisIsolatedFromLiveSubTest`, `DeferredAnalysisTest` |
| 에포크, 멱등성, `reset`, `override` | `GuildMixerProtocolTest`, `ProtocolPrefetchTest` |
| Stale 전환 중단 및 핸드오프 | `StaleXfadeAbortTest`, `CrossfadeMixIntegrationTest`, `XfadeVoicePathTest` |
| 느린 소스에서의 핸드오프 출력 연속성 | `HandoffContinuityTest`, `HandoffBridgeTest` |
| 필터 체인 바인딩 및 재연결 복구 | `MixerFilterExtensionTest`, `ChainDiagnosticTest`, `FeederArbitrationTest`, `FirstTrackIntegrationTest` |
| PCM 믹싱, 게인 램프, `SiphonQueue` | `MixerFilterTest`, `GainRampTest`, `SiphonQueueTest` |
| 콜백 락 안전성 및 디코더 준비 상태 | `PlayerCallbackLockTest`, `OverlapPreparationTest`, `XfadeVoicePathTest` |
| 조성(Key) 추정 및 REST 메타데이터 | `KeyDetectorTest`, `KeyIntegrationTest`, `TransitionFeaturesTest` |
| 전환 콘솔 진단 로그 | `TransitionDiagnosticsTest`, `BeatMatchOutputTest`, `BeatMatchIntegrationTest` |
| 공지 / TTS 오버레이 큐 | `AnnounceQueueTest`, `IdleAnnounceTest` |
| 무음 검출 및 트림 경계 | `SilenceAnalyzerTest`, `TailCutDeciderTest`, `TrimTransitionTest` |
| 음량 정규화 | `NormalizationGainTest`, `NormalizationBaselineTest`, `NormalizationIntegrationTest` |
| BPM, 비트 매칭, Adaptive Automix, DSP | `RealFftTest`, `BpmDetectorTest`, `BpmPrecisionTest`, `BpmGridTest`, `BpmIntegrationTest`, `BpmOctaveTest`, `DownbeatDetectorTest`, `PhraseDetectionTest`, `BeatMatchIntegrationTest`, `BeatMatchOutputTest`, `TempoStretcherTest`, `LinkedTimescaleFilterTest`, `TempoEnvelopeTest`, `AdaptiveRecoveryIntegrationTest`, `AdaptiveRuntimeTest`, `AdaptiveTransitionRaceTest`, `TransitionFeaturesTest`, `AdaptiveTransitionPlannerTest`, `AutomixBoundaryMatrixTest`, `TransitionRecipeTest`, `TransitionMemoryTest`, `BassSwapIntegrationTest`, `CrossoverLR4Test` |

### 테스트 작성 시 주의사항

- **고유 트랙 식별자 사용**: `AnalysisCaches`는 프로세스 내 정적 캐시이므로 분석 테스트 간 캐시 오염을 막으려면 테스트마다 고유한 `identifier`를 사용하세요.
- **리소스 정리**: 테스트에서 생성한 `AudioPlayer`, `AudioPlayerManager`, `ExecutorService`는 `finally` 또는 `@AfterEach`에서 반드시 종료하세요.

---

## 캘리브레이션 및 디버깅

### BPM / Adaptive 캘리브레이션 태스크

실제 로컬 WAV 파일 모음으로 알고리즘 측정치를 추출할 때 사용하는 opt-in Gradle 태스크입니다.

| 목적 | 명령 / 가이드 |
| --- | --- |
| BPM 신뢰도 및 head/tail 오프셋 측정 | `./gradlew -q bpmCalibration -PaudioDir=/absolute/path/to/wavs` ([bpm-calibration.md](bpm-calibration.md)) |
| 트랙 쌍 Adaptive Automix 전략 평가 | [adaptive-calibration.md](adaptive-calibration.md) 참고 (`adaptiveCalibration` 태스크) |

### 전환 로그 확인

전환 의사결정(`strategy=`, 신뢰도 지표, `overlapEnd` 폴백 사유 등)을 상세히 추적하려면 Lavalink 로깅 설정에서 `GuildMixer` 레벨을 `DEBUG`로 높입니다.

```yaml
logging:
  level:
    io.github.mochilabs.mixer.GuildMixer: DEBUG
```

> 시작 요약 로그가 찍힌 뒤에 네이티브 디코더 오류가 발생하면 최종 비트매칭 결과나 `overlapEnd` 폴백 필드가 바뀔 수 있으므로 경고(`WARN`) 및 `overlapEnd` 필드를 함께 점검하세요.

---

## 빌드 버전 및 API 스키마 관리

- **버전 결정 우선순위**: 환경 변수 `MIXER_VERSION` → Gradle 프로퍼티 `-Pversion` → 정확한 Git 태그(앞자리 `v` 제거) → `0.0.0-SNAPSHOT`
  ```bash
  ./gradlew build -Pversion=1.2.3
  ```
- `processResources` 단계에서 플러그인 디스크립터(`src/main/resources/lavalink-plugins/mixer-plugin.properties`)의 `@version@` 토큰이 실제 빌드 버전으로 치환되며, 런타임에서 `MixerVersion.version`이 이를 읽습니다.
- Maven 배포 아티팩트 ID는 `mixer-plugin`이며, 로컬 빌드 JAR 기본 이름은 `RootProject` 이름인 `lavalink_mixer`를 따릅니다.
- **프로토콜 변경 규칙**: REST 요청/응답 DTO 또는 WebSocket `{op: "mixer"}` 페이로드를 수정할 때는 **핸들러 DTO, 매핑 로직, `ProtocolController`, 단위/통합 테스트, [api/openapi.yaml](../api/openapi.yaml), [봇 연동 가이드](../examples/lavalink-client/README.ko.md)** 를 반드시 함께 갱신하세요. 폐기된 필드는 하위 호환 별칭으로 남기지 않고 현재 스키마에서 제거합니다.

---

## 관련 문서

- [아키텍처 레퍼런스](architecture.ko.md) ([English](architecture.md))
- [단일 패스 분석 파이프라인](analysis-pipeline.ko.md) ([English](analysis-pipeline.md))
- [BPM 추출 알고리즘 해설](bpm-extraction.ko.md) · [설계 근거](bpm-detector-design.md)
- [v2 봇 연동 가이드](../examples/lavalink-client/README.ko.md) ([English](../examples/lavalink-client/README.md))
