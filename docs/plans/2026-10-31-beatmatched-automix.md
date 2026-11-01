# Beat-matched AutoMix 구현 계획

## 현재 기준과 후속 계획 — 2026-11-01

이 절은 `2399169`, `060e4f7` 이후의 개발 계획이다. 아래의 0~9절은 최초 설계 기록이며,
구현 전제를 설명하는 문장이나 과거 줄 번호를 현재 작업 지시로 사용하지 않는다.
핵심 AutoMix 기능은 구현되어 있다. 다음 목표는 실제 출력에서 전환 품질을 입증하고,
분석 결과가 부족할 때의 동작과 활성화 방법을 명확하게 만드는 것이다.

### 구현된 범위

| 기존 단계 | 현재 상태 | 후속 작업 |
|---|---|---|
| P0 신뢰도 보정 | 기본 임계값 0.30이 존재한다. 실곡 분포 보정 결과는 이번 검토에서 확인하지 않았다. | 실제 곡으로 오탐·누락을 측정한다. |
| P1 분석 | 피크 보간·옥타브 보정, `BeatGrid(bpm, phaseMs, confidence)`, 머리·꼬리 캐시가 존재한다. | 합성 신호와 실곡에서 오차 및 실패 조건을 검증한다. |
| P2 정책 | `BeatMatchPolicy`, 2/4/8박 페이드, 설정·REST·`planCrossfadeLocked` 연결이 존재한다. | 경계값과 폴백 품질을 검증한다. |
| P3 시프트 | lavadsp 기반 `TempoStretcher`, 소비한 출력 샘플 기반 소스 위치 계산, 종료 시 속도 초기화가 존재한다. | 실제 PCM의 정렬·연속성과 네이티브 실패 경로를 검증한다. |
| P4 키 호환 | 이번 범위에 포함하지 않는다. | 기본 전환의 청취 검증 후 별도 제안으로 다룬다. |
| 마감 | REST 문서, 전환 이벤트, `BeatMatchIntegrationTest`가 존재한다. | 직접적인 킥 시간 오차 검증과 운영 안내를 보강한다. |

사용자는 `060e4f7`에서 `./gradlew build --rerun-tasks`와 전체 141개 테스트의 통과를 보고했다.
앞선 문서 작업에서도 일반 `./gradlew build`의 141개 테스트 통과를 확인했다.
후속 구현에서는 아래 자동 검증을 추가했다. 자동 테스트 결과만으로 실곡 청취 품질이나
Discord 음성 전송까지 검증되었다고 판단하지 않는다.

### 후속 구현 현황

최종 `./gradlew build`에서 154개 테스트가 통과했으며 실패·오류·스킵은 없다.
로컬 보정 도구는 합성 WAV 성공 경로와 존재하지 않는 디렉터리의 오류 종료를 확인했다.

- `BeatMatchOutputTest`에서 원속 2박, 가속 4박, 감속 8박 전환의 실제 출력 PCM을 검증한다.
  좌·우 채널로 소스를 구분하고 생성한 기준 박 시각과 ±20ms 이내인지 비교한다.
  핸드오프 이후 원속 간격과 경계의 반복·누락도 검사한다.
- 폴링으로 시작이 늦어진 전환은 sub PCM을 메인 샘플 시간축에 맞춰 건너뛴다.
  큐의 절대 출력·소스 위치는 underflow, overflow, 부분 읽기를 포함해 관리한다.
- 큐는 PCM 생성 당시 속도를 저장한다. 네이티브 실패 후에도 앞서 생성한 PCM을
  원속으로 잘못 계산하지 않는다. 늦은 네이티브 실패는 종료 이벤트의 폴백 상태에 반영한다.
- BPM만 활성화된 ready 트랙도 미뤄진 분석을 재시도한다.
  박을 찾지 못한 정상 완료는 반복 디코딩 대상으로 취급하지 않는다.
- [로컬 보정 도구](../bpm-calibration.md)는 WAV별 머리·꼬리 분석 결과를 TSV로 저장한다.
  합성 클릭·무음으로 실행 경로를 검증했으며 실곡 임계값을 보정한 결과는 아니다.
- 활성화에 필요한 세 설정과 폴백을 README·OpenAPI에 명시했다.
  기존 TypeScript helper는 v1이므로 v2와 호환되지 않는다고 가이드에 표시했다.

실곡 20~30개와 기준 라벨을 이용한 임계값 결정, 실제 Lavalink·Discord 청취 검증은 남아 있다.
TypeScript helper의 v2 전환과 키·프레이즈 인식은 이번 구현에 포함하지 않았다.

### 삽입 위치와 책임

기존 모듈을 유지한다. `BeatMatchPlanner`라는 중복 구현을 추가하지 않는다.

- `analysis/BpmDetector.kt`와 `TrackAnalysisService.kt`가 머리·꼬리 로컬 격자를 만든다.
  `analyzeGrid(..., windowStartMs)`는 구간 시작 위치를 더한 트랙 좌표의 `phaseMs`를 반환한다.
- `analysis/BeatMatchPolicy.kt`가 비트매치, equal-power, gapless 결정을 만든다.
  `GuildMixer.planCrossfadeLocked()`는 설정과 캐시를 전달하는 연결 지점이다.
- `audio/TempoStretcher.kt`는 sub 생산 체인에서만 템포를 바꾼다.
  메인 믹서 소비 경로에 DSP나 분석을 추가하지 않는다.
- `GuildMixer.startOverlap()`과 `doHandoff()`는 플레이어 전환과 상태 검증을 담당한다.
  추가 분석 알고리즘이나 정책은 별도 파일에 둔다.

현재 시프트 방식은 제안 C인 Lavalink 런타임의 lavadsp 재사용이다.
의존성과 통합 테스트가 이미 있으므로 C의 도입 가능성을 다시 조사하는 단계는 생략한다.
목표 배포 환경의 네이티브 라이브러리 동작은 출시 검증에서 확인한다.
별도 WSOLA 구현이나 음정까지 바뀌는 리샘플링은 이번 범위에 추가하지 않는다.

### 템포와 핸드오프 정책

기본안은 오버랩 동안 `r = outgoingBpm / incomingBpm`을 유지하고,
메인으로 넘길 때 원속으로 재생하는 현재 동작이다.
60% 고정·40% 복귀 램프는 후반의 비트 간격을 바꾸므로 기본안에 넣지 않는다.
원속 전환이 청취상 문제가 되는 경우에만 비교 실험으로 평가한다.

위치 보정과 속도 복귀는 별개의 문제다. 원속으로 복귀해도 앞서 소비한 소스 위치는 보정해야 한다.
현재 큐는 각 PCM 조각의 생성 당시 속도로 소스 진행량을 계산한다.
`takeAt()`은 늦은 시작으로 건너뛴 구간과 overflow로 잃은 구간까지 절대 위치에 반영한다.
예를 들어 `r=0.96`에서 출력 1초는 소스 약 0.96초에 해당한다.
이 값은 컨버터가 직접 보고한 소비량이 아니라 출력 샘플과 생성 당시 속도에 기초한 계산이다.
네이티브 실패 전후에 서로 다른 속도의 PCM이 큐에 남는 경우는 회귀 테스트로 검증한다.

### 다음 작업 순서

#### 1. 믹스 출력의 정렬과 핸드오프를 직접 검증한다

`BeatMatchIntegrationTest`는 오버랩 출력의 추정 BPM과 속도 복귀를 확인한다.
직접적인 정렬 검증은 새 `BeatMatchOutputTest`가 담당한다.
추정 BPM이 맞다는 사실만으로 두 소스의 킥이 같은 시점에 발생했다고 판단할 수 없다.

- 서로 구별할 수 있는 합성 클릭 소스를 사용해 각 소스의 실제 출력 onset 시간을 측정한다.
- 원속, 가속, 감속과 2/4/8박 전환을 포함한다. 시작 위상, 선행 무음, 짧은 곡을 바꿔 실행한다.
- 분석기 자체를 정답 판정기로 재사용하지 않고, 생성한 클릭의 기준 시각과 비교한다.
- 핸드오프 전후의 소스 연속성, 반복·누락 구간, 실제 원속 재생을 확인한다.
  핸드오프 이후 출력 구간을 반드시 확보해 검증이 조건문 때문에 생략되지 않게 한다.

제안 완료 기준은 안정 구간에서 대응 킥의 시간 오차가 ±20ms 이내인 것이다.
초기 지연과 핸드오프 구간도 별도로 기록하며 실패 구간을 임의로 제외하지 않는다.
목표를 넘으면 소스 위치 계산, 프리필, 계획 시각과 실제 시작 시각의 차이를 먼저 조사한다.

주요 대상은 `BeatMatchIntegrationTest`, `TempoStretcherTest`, `SiphonQueueTest`다.
문제가 재현되면 해당 소유 모듈에 최소 수정 후 기존 전환 테스트를 함께 실행한다.

#### 2. 신뢰도 임계값을 실곡으로 보정한다

사용 가능한 실제 곡 20~30개에서 규칙적 비트, 가변 템포, 약한 비트, 앰비언트 구간을 고른다.
사용 가능한 음원과 기준 BPM·박 시각 확보가 이 단계의 선행 조건이다.
원격 서비스에서 음원을 자동 수집하는 작업은 포함하지 않는다.

머리와 꼬리에 대해 BPM 오차, 위상 오차, confidence, 비트매치 허용 여부를 기록한다.
보정에 사용한 곡과 최종 평가 곡을 나누고, 잘못 허용한 전환과 놓친 전환을 함께 집계한다.
기존의 “분포가 겹치지 않는다”는 완료 조건은 사용하지 않는다.
오탐이 남으면 임계값만 조절하지 말고 해당 구간에서 폴백할 조건을 검토한다.
최종 임계값과 허용할 오탐 수준은 이 결과를 근거로 결정하며, 지금 0.30의 적절성을 단정하지 않는다.

#### 3. 분석 준비 상태와 활성화 계약을 정리한다

현재 beat matching에는 `crossfadeEnabled`, `bpmDetectionEnabled`, `beatmatchEnabled`가 모두 필요하다.
`updateBeatmatch()`는 BPM 분석을 자동 활성화하지 않는다.
우선 기존 REST 계약을 유지하고 필요한 설정과 `bpm_disabled` 폴백을 문서와 예제에 명시한다.

JIT 요청부터 분석 완료까지의 시간을 기록해 전환 시작 전에 격자가 준비되는지 확인한다.
분석이 늦으면 재생을 기다리게 하지 않고 기존 전환을 유지한다.
지연이 재현되기 전에는 JIT 창 확대나 새로운 사전 분석 API를 추가하지 않는다.

현재 나가는 곡은 꼬리 격자가 없으면 머리 격자로 대체한다.
긴 곡이나 가변 템포에서 이 대체가 오정렬을 만드는지 검증한다.
문제가 확인되면 신뢰할 수 있는 꼬리 격자가 없을 때 equal-power로 전환하는 정책을 검토한다.
설정 변경 후 같은 곡의 캐시를 재사용하는 동작도 확인한다. 캐시 키 확장은 재현된 요구에 맞춘다.

#### 4. 실패 경로와 실제 배포를 검증한다

네이티브 생성·처리 실패, sub underflow, 분석 지연, seek, skip, STOP, 재연결을 오버랩 중에 발생시킨다.
오래된 트랙으로의 handoff와 중복 advance가 없는지 확인한다.
`beatMatched`와 폴백 사유가 실제 오디오 동작을 설명하는지도 확인한다.
알 수 없는 길이와 seek 불가 트랙은 각 경로의 실제 폴백을 검사한다.

대상 서버 환경에서 JAR을 로드하고 봇으로 연속 재생·큐 변경·공지 혼합을 수행한다.
클릭 트랙과 실곡 모두에서 전환을 녹음해 일반 crossfade와 비교한다.
원속 복귀의 부자연스러움이 확인될 때만 60/40 램프안을 별도 실험한다.
램프 실험에는 구간별 소스 진행량과 큐에 남아 있는 PCM의 속도 관계에 대한 검증을 포함한다.

### 출시 조건과 제외 범위

출시 전에는 직접적인 출력 정렬 테스트, 실패 경로 테스트, 대상 환경의 청취 확인 결과를 남긴다.
코드 변경 후 관련 테스트와 전체 `./gradlew build`를 실행한다.
REST·이벤트 변경이 있으면 `api/openapi.yaml`, README, 클라이언트 예제를 함께 갱신한다.
기존 `nextTrackFirstBeatMs` 호환 필드는 유지한다.

기본값은 계속 옵트인이다. 키 호환, 다운비트·프레이즈 인식, 자동 선곡,
메인 체인 템포 조절은 이번 후속 작업에 포함하지 않는다.

## 최초 설계 기록

> **이 문서의 대상**: 이 프로젝트(Lavalink 믹서 플러그인)와 오디오 DSP를 전혀 모르는 사람.
> 용어부터 현재 코드 구조, 왜 이렇게 구현하는지, 어느 파일의 어느 심볼을 고치는지까지 전부 적었다.
> 코드 식별자(클래스/함수/변수 이름)는 영어로 두고, 설명은 한국어로 썼다.

---

## 0. 한 문장 요약

지금 믹서는 곡과 곡을 이을 때 "시간이 겹치는 페이드(크로스페이드)"만 한다.
여기에 **박자(비트)를 분석해서 두 곡의 박이 겹치도록 들어오는 곡의 재생 속도를 아주 약간(최대 ±12%) 조절**한 뒤
2·4·8박 단위로 겹쳐 넘기는 기능을 추가한다.
분석이 실패하거나 박자를 못 잡으면 **지금과 똑같은 크로스페이드(또는 무간격 재생)로 자동 폴백**한다. 절대 재생이 끊기지 않는다.

---

## 1. 배경: 지금 뭐가 문제인가

두 곡이 이어질 때 지금 하는 일:

1. 나가는 곡이 끝나가면 서버가 다음 곡을 "예열(preload)"한다.
2. 끝나기 직전(`crossfadeMs` 이전)부터 나가는 곡의 볼륨을 줄이고, 들어오는 곡의 볼륨을 올리며 겹친다(equal-power crossfade).
3. 정확히 겹치는 구간이 끝나면 들어오는 곡이 메인 플레이어로 넘어간다(handoff).

문제: **이 겹침은 "시간"으로만 맞춘다.** 박자는 전혀 보지 않는다.
DJ가 두 곡을 자연스럽게 잇는 핵심은 두 곡의 **박(비트)이 같은 순간에 울리게** 하는 것이다.
박이 어긋난 채로 5초 겹치면 듣기에 부자연스럽다(킥 드럼이 서로 어긋나서 울린다).

이 기능은 "박을 맞춘 크로스페이드(beat-matched crossfade)"를 만든다.

### 1.1 하지 않는 것 (범위 제한)

- **마디(phrase)·다운비트(첫 박)·곡 구조 인식은 하지 않는다.** 박의 "주기(간격)"와 "위상(어느 시점에 박이 있는지)"만 잡는다.
- **키(key) 호환 판정(P4)은 선택 사항**이고, P0~P2까지는 없어도 동작한다.
- **메인 곡·공지(TTS)·병렬 오버레이에는 템포 조절을 절대 적용하지 않는다.** 오직 크로스페이드로 들어오는 곡(sub 트랙)에만 적용한다.

---

## 2. 핵심 용어 사전

이 문서를 읽는 데 필요한 용어만. 더 깊은 DSP 내용은 해당 단계에서 설명한다.

| 용어 | 뜻 |
|---|---|
| **BPM** | Beats Per Minute. 1분당 박 수. 120 BPM = 1초에 2번 박이 온다. |
| **박 주기(period)** | 한 박에서 다음 박까지의 시간(밀리초). `periodMs = 60000 / bpm`. 120 BPM → 500ms. |
| **박 위상(phase)** | 분석한 구간에서 "첫 박이 몇 ms 지점에 있는가". 주기와 위상 둘이 있어야 "박이 어디서 울릴지"를 예측할 수 있다. |
| **박 격자(beat grid)** | 주기 + 위상 + 신뢰도를 묶은 것. 이 격자 위에 박이 규칙적으로 놓인다고 가정한다. |
| **크로스페이드(crossfade)** | 나가는 곡 볼륨을 줄이면서 들어오는 곡 볼륨을 올려 겹치는 것. |
| **핸드오프(handoff)** | 겹침이 끝나고 들어오는 곡이 메인 플레이어로 넘어가는 시점. |
| **sub 트랙 / sub 플레이어** | 크로스페이드 중 들어오는 곡을 재생하는 숨겨진 보조 플레이어. 메인 플레이어와 별개다. |
| **siphon(사이펀)** | sub 플레이어가 디코딩한 PCM 오디오를 담는 유한 크기 큐. 메인 체인의 믹서 필터가 여기서 PCM을 빼서 섞는다. |
| **PCM** | 디코딩된 원시 오디오 샘플(float). Discord에 보내기 직전의 형태. |
| **템포 시프트(time stretch)** | **음정(pitch)은 유지한 채** 재생 속도(템포)만 바꾸는 것. 그냥 빠르게/느리게 틀면(리샘플링) 음정도 같이 올라/내려가서 노래가 어색해진다. |
| **rate ratio (r)** | 들어오는 곡에 적용할 속도 비율. `r = outgoingBpm / incomingBpm`. r=1.0이면 원래 속도. r>1이면 빠르게, r<1이면 느리게. |
| **maxTempoChange** | 허용하는 최대 템포 변화량. 예: 0.06이면 r이 0.94~1.06 사이일 때만 박 매칭을 시도하고, 그 밖이면 폴백. |
| **컴파일 전용(compileOnly)** | 이 프로젝트가 컴파일할 때만 필요한 의존성. 실행 시점엔 Lavalink 서버가 이미 제공하므로 jar에 포함하지 않는다. |

---

## 3. 현재 시스템 구조 (새로 온 사람용)

프로젝트는 **Lavalink v4 플러그인**이다. Lavalink 서버 코드는 건드리지 않고, 플러그인 jar만 서버 `plugins/`에 넣는다.

### 3.1 핵심 파일과 역할

| 파일 | 역할 |
|---|---|
| `src/main/kotlin/dev/lavalink/mixer/plugin/GuildMixer.kt` | **길드(서버)별 조정자.** 큐·전환 상태·오버레이·핸드오프 전부 여기. 프로젝트의 심장. |
| `.../plugin/audio/Siphon.kt` | sub 플레이어에서 PCM을 빼내는 `SiphonQueue`/`SiphonFilter`/`SiphonFactory`. |
| `.../plugin/audio/MixerFilter.kt` | 메인 플레이어 체인에 붙는 필터. sub PCM을 메인에 섞는다. |
| `.../plugin/analysis/BpmDetector.kt` | PCM에서 BPM을 추정하는 순수 Kotlin 분석기. |
| `.../plugin/analysis/TrackAnalysisService.kt` | 곡을 한 번 더 디코딩해서 분석(무음/BPM/라우드니스)하는 서비스. |
| `.../plugin/analysis/AnalysisCaches.kt` | 곡 ID별 분석 결과 캐시. |
| `.../plugin/MixerConfig.kt` | 설정 기본값(`application.yml`의 `mixer:` 바인딩). |
| `.../plugin/protocol/MixerRestHandler.kt` | `/mixer/*` REST 엔드포인트. |
| `api/openapi.yaml` | REST 스펙 문서. |
| `build.gradle.kts` | 빌드/의존성 선언. |

### 3.2 전환 흐름 (지금 동작)

```
[곡이 끝나감]
   └─ decidePollLocked()  (GuildMixer.kt:860)
         └─ TrimTransition.crossfade(contentEnd, crossfadeMs)  →  CrossfadePlan(startMs, handoffMs, durationMs)
               └─ startOverlap(plan)  (GuildMixer.kt:1302)
                     ├─ sub 플레이어에 들어오는 곡 playTrack()
                     ├─ prefillSubSiphon()  ← sub 디코더를 미리 돌려 siphon을 채움
                     └─ xfadeActive = true, subOutputSamples = 0
               └─ MixerFilter.process()  ← 매 20ms 청크마다 호출 (보이스 스레드)
                     ├─ advance()          ← 게인 램프 전진
                     ├─ takeSub(length)    ← siphon에서 PCM 빼냄
                     ├─ noteSubOutput(subSamples)  ← "들린 sub 샘플 수" 누적
                     └─ main*mainGain + sub*subGain 섞음
               └─ doHandoff()  (GuildMixer.kt:1436)
                     ├─ outputPosition = subStartPositionMs + subOutputSamples*1000/SAMPLE_RATE
                     └─ 들어오는 곡의 clone을 outputPosition으로 seek → 메인 플레이어로 승격
```

**중요한 전제(현재 코드의 암묵적 가정):** 핸드오프 위치 계산이
`outputPosition = subStartPositionMs + (들린 출력 샘플 수)`인데, 이건 **"sub 트랙이 원래 속도(r=1.0)로 재생됐다"**일 때만 맞는다.
템포 시프트를 넣으면 "들린 출력 샘플 수"와 "소스(원본 곡)에서 실제로 소비된 위치"가 달라져서 이 식이 틀어진다. → 이게 이 계획의 핵심 난제 중 하나(§5.3).

### 3.3 분석 경로 (현재 동작)

분석은 **재생에 쓰는 sub 플레이어와 완전히 별개**의 "분석용 플레이어"를 쓴다.

```
TrackAnalysisService.analyze(track, settings)
   └─ ensureAnalysisPlayer()   ← 분석 전용 AudioPlayer + SiphonFactory (TrackAnalysisService.kt:169-179)
   └─ collectFrom(track, factory, startMs, capSamples)  (line 340)
         └─ clone.position = startMs   ← 원하는 지점부터 디코딩 가능 (꼬리 분석에 씀)
```

이 "분석용 플레이어 분리"는 뒤에서 중요하다. 템포 시프트를 어디에 꽂아도 **분석 경로는 건드리지 않는다**는 뜻이기 때문이다(§5.2).

### 3.4 캐시 구조

`AnalysisCaches`(AnalysisCaches.kt)는 곡 ID(`idOf(track)`)를 키로 하는 유한 캐시 3개를 갖는다:

```kotlin
val norm = BoundedCache<Float>()                       // 라우드니스
val trim = BoundedCache<TrimPoints>()                  // 무음(머리/꼬리)
val bpm  = BoundedCache<BpmDetector.BpmResult>()       // BPM
```

**이 계획에서 주의:** BPM 캐시가 곡 ID만 키로 쓴다. 그런데 beat matching은 "들어오는 곡의 머리"와 "나가는 곡의 꼬리"를 **따로** 분석해야 하므로, 키에 "머리/꼬리 구분"이 들어가도록 구조를 바꿔야 한다(§6 P1).

---

## 4. 목표와 비목표

### 4.1 목표

1. 곡별로 **박 격자(주기 + 위상 + 신뢰도)**를 추정한다.
2. 두 곡의 박 격자가 충분히 신뢰할 만하고, 템포 차이가 `maxTempoChange` 안이면:
   - 들어오는 곡의 시작 위치를 나가는 곡의 박에 맞춰 계산하고,
   - 들어오는 곡을 `r` 비율로 템포 시프트해서 박이 겹치게 재생하고,
   - 페이드 길이를 2·4·8박 중 하나로 정한다.
3. 조건이 안 맞으면 **지금의 크로스페이드/무간격 경로를 그대로 탄다(폴백).**
4. 핸드오프·취소·seek·교체·디코더 실패 시 템포를 반드시 1.0으로 되돌리고 프로세서 상태를 초기화한다.

### 4.2 비목표

- 다운비트/마디/프레이즈 인식 (첫 버전은 "박 격자 정렬"만).
- 메인 트랙·공지·병렬 오버레이의 템포 변경.
- 분석 실패를 막기 위해 곡 승격(promotion)을 지연시키는 것. 분석은 항상 best-effort.

---

## 5. 핵심 결정 (왜 이렇게 하는가)

구현 전에 반드시 확정해야 할 네 가지. 각각 근거와 함께.

### 5.1 D1 — 템포 시프트 라이브러리: **Lavalink 서버의 timescale 재사용 (확정)**

**결정:** 새 라이브러리를 새로 구현하거나 별도 JNI 바인딩을 쓰지 않고, Lavalink 서버가 이미 번들로 갖고 있는
**`com.github.natanbc.lavadsp`(lavadsp)의 `TimescalePcmAudioFilter`** 를 재사용한다.

**이미 검증된 사실 (이 저장소와 로컬 캐시에서 확인):**

| 항목 | 확인 결과 |
|---|---|
| 서버에 lavadsp 포함 | `LavalinkServer/build.gradle.kts`에 `implementation(libs.lavadsp)` → 런타임 클래스패스에 있음 |
| 버전 | `settings.gradle.kts`: `dev.arbjerg:lavadsp:0.7.8` |
| 플러그인에서 접근 가능 | 서버 `PluginManager.kt`가 `URLClassLoader.newInstance(jarURLs, javaClass.classLoader)`로 만든다. **부모 우선(parent-first)**이라 서버가 가진 클래스를 플러그인 코드에서 그대로 resolve 가능 |
| 네이티브 라이브러리 | lavadsp jar 자체에 번들: `natives/{linux-x86-64, linux-aarch64, linux-musl-x86-64, linux-musl-aarch64, darwin, win-*}/libtimescale.so` (+AVX2 변형). musl 포함 → 공식 Docker 이미지에서도 동작 |
| 로드 방식 | `TimescaleNativeLibLoader`가 `NativeLibLoader.create(TimescaleNativeLibLoader.class, ...)`로 **lavadsp 자기 jar의 클래스로더**에서 추출. 플러그인 클래스로더와 무관 |
| 의존성 충돌 | lavadsp 0.7.8의 의존성은 `slf4j-api` + `native-loader` **둘뿐**. lavaplayer 의존성 없음 → 버전 충돌 없음 |

**플러그인 쪽 필요한 변경은 한 줄:**

```kotlin
// build.gradle.kts (기존 compileOnly 블록에 추가)
compileOnly("dev.arbjerg:lavadsp:0.7.8")
```

- `compileOnly`이므로 플러그인 jar에 lavadsp를 굽지(shade) 않는다. 실행 시점엔 서버가 제공한다.
- `maven.lavalink.dev`는 이미 `build.gradle.kts`의 repositories에 등록돼 있어서 resolve 가능.
- `TimescalePcmAudioFilter`는 `FloatPcmAudioFilter`(`process(float[][], int, int)`)만 구현하고, 생성자가 `(downstream, channels, sampleRate)` 원시값이라
  `lavaplayer:2.2.6`과 시그니처 충돌 없음. (Lavalink v4가 이미 이 조합으로 운영 중이라 검증됨)

**왜 별도 JNI 바인딩(soundtouch-jni 등)보다 나은가:**
- `TimescalePcmAudioFilter`는 이미 `FloatPcmAudioFilter`라 sub 체인에 그대로 꽂힘. 별도 JNI 바인딩은 raw JNI라 `FloatPcmAudioFilter` 어댑터 + `.so` 재번들 + 자체 로더를 직접 만들어야 함(코드·배포 표면 증가).
- 플러그인은 이미 `lavaplayer`/`plugin-api`를 서버 제공에 의존(`compileOnly`)하고 있으므로, lavadsp 하나 더 의존하는 건 같은 계약의 연장일 뿐.

**반드시 지킬 안전장치 (fail-closed):**
`TimescaleConverter` 생성자가 네이티브를 로드하다 실패하면(`UnsatisfiedLinkError`) sub 체인 생성이 통째로 죽을 수 있다.
기존 설계 철학("믹서 버그는 passthrough로 강등, 절대 무음 안 만듦")에 따라, **생성 실패 시 로그만 남기고 템포 시프트 없이 정상 재생으로 폴백**해야 한다.

### 5.2 D2 — 시프트 삽입 위치: **sub 체인(생산 측), 보이스 스레드 밖**

**먼저 지켜야 할 불변식: 보이스 스레드는 실시간이고 지금도 lock-free·저비용이다.**

코드로 확인한 스레드 모델:

| 스레드 | 무엇이 도는가 | 근거 |
|---|---|---|
| **보이스(Koe 오디오) 스레드** | `MixerFilter.process()` → `advance()` → `takeSub()` → 섞기 | MixerFilter.kt:33-37, GuildMixer.kt:553 "Lock-free on purpose: this runs on the voice thread for every ~20ms chunk" |
| **스케줄러 스레드** | `MixerPlugin.loop()` → `mixer.drain()` → `sub.provide()` → sub 체인 | MixerPlugin.kt:31,41 (단일 스케줄러 + `scheduleAtFixedRate(::loop, 0, 20ms)`) → loop()가 `mixer.drain()` 호출 (MixerPlugin.kt:140) |
| **컨트롤 스레드** | `startOverlap()` → `prefillSubSiphon()` → `sub.provide()` | poll()/REST에서 호출 |

핵심: **sub 디코더(`sub.provide()`)와 그 안의 필터 체인은 drain/prefill이 호출하는 스케줄러·컨트롤 스레드에서 돈다. 보이스 스레드가 아니다.**

**SoundTouch는 청크당 실시간 DSP다. 보이스 스레드에 인라인으로 넣으면 안 된다.**
20ms 안에 끝나야 하는 청크에서 SoundTouch가 버스트로 느려지면 오디오 끊김(stutter)이 난다.
지금 `MixerFilter`는 "잠금 없이 저비용, idle이면 passthrough"가 설계 전제인데, 거기에 DSP를 얹으면 이 전제가 깨진다.

따라서 후보 위치와 판정:

| 위치 | 스레드 | 문제 |
|---|---|---|
| (a) drain 쪽: `MixerFilter.process` 안, takeSub와 섞기 사이 | **보이스 스레드** | SoundTouch가 실시간 스레드에서 돈다 → stutter 위험. **기각** |
| (b) 생산 측: sub 체인 `decoder → TimescalePcmAudioFilter → SiphonFilter` | **스케줄러/컨트롤 스레드** (drain/prefill이 provide() 호출) | siphon이 "시프트된(출력 속도) PCM"으로 채워져 계정이 출력 도메인으로 바뀜 → D3의 소스 카운터로 해결 |

**결정: (b) 생산 측.** sub 디코더 체인에서 시프트한다. 보이스 스레드는 지금처럼 `takeSub`(ArrayDeque에서 복사)와 섞기만 한다.

**분석 경로·다른 경로와의 격리(중요):**
- 분석은 **별도** `analysisPlayer`의 `SiphonFactory`를 쓴다(`TrackAnalysisService.kt:169-179`).
  라이브 sub 체인에 분석 탭을 붙이는 코드는 없다(확인: `addTap`/`removeTap` 호출이 그 파일에만 있음).
- 따라서 시프트를 **라이브 sub 체인에만** 걸어야 한다. `SiphonFactory`에 시프트 옵션(기본 꺼짐)을 넣고,
  분석 플레이어·병렬 오버레이·메인 체인·공지는 그 옵션을 **끈 채** 그대로 쓴다. 절대 메인/공지/오버레이에 걸지 않는다.

### 5.3 D3 — 소스 좌표와 출력 좌표 분리 (핸드오프 위치 정확성)

**문제(현재 코드의 암묵적 전제):** 핸드오프 위치가 이렇게 계산된다(GuildMixer.kt:1486-1491):

```kotlin
val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
outputPosition = subStartPositionMs + subOutMs
```

`outputPosition`은 **들어오는 곡의 원본 타임라인(source) 좌표**다. 핸드오프에서 이 값으로 `handoff.clone.position`을 seek하기 때문이다.
그런데 `subOutputSamples`는 `noteSubOutput()`(GuildMixer.kt:642)이 쌓는 값이고, `MixerFilter.process()`가 `takeSub(length)`로 뺀
**들린(출력/벽시계) 샘플 수**를 더한다. r=1.0이면 소스 위치와 들린 위치가 같아서 맞다. **r≠1이면 틀어진다.**

- 6% 시프트 × 5초 크로스페이드 → 최대 ~300ms 어긋남 (반복/스킵으로 들림)
- 30초 크로스페이드면 ~1.8초 → 메인 트랙이 엉뚱한 위치에서 재개

**D2(생산 측 시프트)를 반영한 구체적 계약:**

생산 측 시프트(decoder → timescale → siphon)를 택하면, **siphon은 "시프트된(출력 속도) PCM"을 담는다.**
따라서 `takeSub`/`noteSubOutput`은 자연스럽게 출력 도메인이 된다 — 계정의 단위는 그대로 "들린 샘플"이고, 기아/prefill 판정은 지금 의미 그대로 유지된다.

핸드오프 위치만 **소스 좌표로 환산**해야 한다. 시프트 비율 r일 때, 들린 출력 샘플 `N`은 소스 샘플 `N * r`에 해당한다.
아래 계산은 고정 속도에서의 근사이며, 현재 구현은 청크별 `effectiveSpeed`로 누적한다:

```kotlin
// D2 = 생산 측 시프트 기준
val sourceSamples = subOutputSamples.get() * r   // r은 그 전환의 고정 비율
outputPosition = subStartPositionMs + sourceSamples * 1000 / SAMPLE_RATE
```

**최초 설계의 대안: 컨버터가 보고하는 실제 소스 소비량을 쓰는 것. 현재 구현된 방식은 아니다.**
`TimescaleConverter`는 `getInputOutputSampleRatio()`와 `numUnprocessedSamples()`를 노출한다.
부동소수점 r을 곱하는 근사 대신, 컨버터가 "이번 청크에서 소스 샘플을 몇 개 소비했는지"를 직접 보고하게 해서
`subSourceSamples`에 누적하는 편이 드리프트가 없고, SoundTouch 내부 버퍼(초기 지연·flush 전 잔여)까지 정확히 반영한다.

```kotlin
// 견고안: 컨버터 보고 기반
// sub 체인(생산 측)의 timescale 래퍼가 청크마다 소스 소비량을 보고 → GuildMixer.subSourceSamples 누적
outputPosition = subStartPositionMs + subSourceSamples * 1000 / SAMPLE_RATE
```

**결정: `subSourceSamples`(소스 소비 누적)를 신설하고, 핸드오프 위치는 이걸로 계산한다.**
기존 `subOutputSamples`(출력/벽시계)는 오버레이 진행도·로깅·기아 판정에 그대로 쓴다. 오직 핸드오프 seek 좌표만 소스 카운터로 바꾼다.

> **주의(참고만):** lavaplayer의 `sub.position`(`subReportedMs`)은 체인 끝 frame buffer의 출력 소비로 갱신되어,
> 시프트 앞이든 뒤든 벽시계를 따라간다 [INFERENCE: lavaplayer 내부 미열람]. 위치 계산에 `sub.position`은 쓰지 않고,
> 위처럼 컨버터 소스 소비 누적을 쓴다. lavaplayer 내부에 의존하지 않는다.

> **SoundTouch 초기 지연(버퍼링):** `TimescalePcmAudioFilter`는 입력을 넣어도 처음 몇 청크는 출력이 안 나올 수 있다
> (`getInitialLatencies()`, `getNominalInputSequences()`). 생산 측 시프트라면 `prefillSubSiphon()`(GuildMixer.kt:1409)이 이미
> 이 지연을 흡수하는 버스트 프리필을 하므로(현재 600프레임 버스트, line 1414) 별도 소비 측 버퍼 없이 흡수될 가능성이 높다.
> **P3 스파이크에서 실제로 기아 없이 프리필되는지 실측**으로 확정한다. 부족하면 프리필 버스트 상한을 조정한다.

### 5.4 D4 — r을 고정 vs 복귀 램프

초안에는 "마지막 40% 구간에서 r을 1.0으로 되돌린다"가 있었다. **이걸 안 한다.**

이유:
1. **위치 드리프트를 안 없앤다.** 앞 60% 동안 쌓인 소스/출력 차이는 그대로 남는다. r=1.0 복귀는 "재개 시점의 속도"만 맞추지 "재개 시점의 위치"는 못 맞춘다. 위치 정확성은 D3의 소스 카운터가 해결한다.
2. **비트 정렬이 눈에 띄게 무너진다.** 겹치는 동안 들어오는 곡 템포가 원래로 되돌아가며 박이 다시 어긋나기 시작한다. "박 유지" 목표와 상충.

**결정(표준 DJ 방식과 동일):** 겹침(크로스페이드) 구간 동안에는 **r을 일정하게 유지**한다.
핸드오프 시점에 메인 플레이어가 그 곡을 **원래 속도(r=1.0)**로 이어받는다. 이때 오디오 신호는 연속이라 클릭이 없고,
핸드오프 이후엔 각 곡이 자기 본래 템포로 재생되는 것이 표준 동작이다.

---

## 6. 단계별 상세

각 단계는 독립적으로 커밋·검증 가능하게 나눴다. **P2까지 완성되면 이미 쓸 만하다** — 템포가 비슷한 곡끼리는 박이 맞는 전환이 되고, 아닌 곡은 지금처럼 크로스페이드로 간다.

### P0 — 신뢰도(confidence) 임계값 보정 (크기: S)

**목표:** BPM 신뢰도 점수가 "박자 있는 곡"과 "박자 없는 곡"을 실제로 가르는지 확인하고, 쓸 임계값을 정한다.

**무엇을 하나:**
- `BpmDetector.kt`의 `BpmResult.confidence`(이미 존재)를 로그로 남긴다.
- 실제 곡 20~30개를 돌려 신뢰도 분포를 수집한다.
- "박자 있는 곡"과 "없는 곡(앰비언트/노이즈)"이 분포상 갈리는 임계값을 고른다.

**완료 기준:** 박자 있는 곡/없는 곡의 신뢰도 분포가 겹치지 않고 임계값 하나를 정할 수 있다.

**주의:** 이건 실제 곡 없이는 못 정한다. BPM 정답(메타데이터 등)이 있는 곡이면 P1 검증에도 재사용한다.

### P1 — 분석 업그레이드 (크기: M~L)

**목표:** "곡 전체 BPM"이 아니라 **전환 지점 근처의 로컬 템포와 박 위상**을 정확히 잡는다.

**왜 필요한가(현재 한계):**
`BpmDetector.kt`는 초당 100프레임(`FRAMES_PER_SECOND = 100`) = **10ms 단위 lag**로 자기상관을 계산한다.
이러면 BPM이 120 → 122.4 → 117.6처럼 **약 2% 간격**으로만 나온다(드리프트 테스트에서 실제 122.4가 나옴).
곡 앞 구간만 분석해 뒤로 격자를 연장하면 ±1% 오차만 있어도 3분 뒤엔 ~1.8초 어긋난다. 전환에 필요한 정밀도는 수십 ms다.

**무엇을 하나:**
1. 자기상관 피크 **보간(interpolation)**으로 BPM 해상도를 2% 간격에서 수십 ms 정밀도로 올린다.
2. **×2/×½ 옥타브 후보 보존**: 60 BPM이 120으로, 또는 그 반대로 잘못 잡히는 문제를 후보군으로 해결한다.
3. **머리/꼬리 분리 분석**: 들어오는 곡은 머리, 나가는 곡은 꼬리 윈도우를 분석한다.
   - `TrackAnalysisService.collectFrom(track, factory, startMs, capSamples)`(line 340)가 시작 위치를 지정할 수 있으므로 꼬리 스캔이 가능하다.
4. 새 타입 `BeatGrid(periodMs, phaseMs, confidence)`을 도입한다. `BpmResult.firstBeatMs`의 "다운비트"라는 오해 소지 있는 이름도 `phaseMs`로 바꾼다(이 값은 실제로 첫 onset이지 다운비트가 아니다).

**완료 기준:**
- 합성 신호에서 위상 오차 ±20ms 이내.
- 지터 있는 120 BPM이 60 BPM으로 나오는 문제(옥타브 오류)가 해결됨.

**캐시 구조 변경:** 곡 ID만 키로 쓰는 `AnalysisCaches.bpm`을, **머리/꼬리 구분까지 키에 넣는 구조**로 바꾼다. 예: `BoundedCache<BeatGrid>` 두 개(head/tail) 또는 키에 suffix.

### P2 — 게이트 + 정렬 전환 (크기: M)

**목표:** 양쪽 박 격자가 신뢰할 만하고 템포 차이가 한도 안일 때만 박 매칭을 켜고, 페이드를 박 단위로 스냅한다.

**무엇을 하나:**
1. 설정 `maxTempoChange`(0~0.12, 기본 0.06)를 추가하고 REST/설정 검증을 붙인다.
2. 양쪽 confidence와 템포 차이로 **beat-match / 폴백**을 판정하는 순수 정책을 만든다(입출력만 있고 부작용 없음).
3. 페이드 길이를 **2/4/8박 단위**로 스냅한다.
4. 들어오는 곡의 박을 나가는 곡의 박에 맞추는 시작 위치를 계산한다.

**완료 기준:** 템포가 비슷한 곡끼리 킥이 겹쳐서 들어간다(합성 클릭 트랙으로 확인).

### P3 — 템포 시프트 (크기: L)

**목표:** 들어오는 곡을 `r = outgoingBpm / incomingBpm`로 시프트해서 겹침 동안 박을 유지하고, 핸드오프에서 원속(r=1.0)으로 이어받는다.

**무엇을 하나:**
1. `build.gradle.kts`에 `compileOnly("dev.arbjerg:lavadsp:0.7.8")` 추가 (D1).
2. **생산 측(D2)**: `SiphonFactory`에 시프트 옵션(기본 꺼짐)을 추가하고, 켜지면 체인을
   `decoder → TimescalePcmAudioFilter → SiphonFilter → siphon`으로 만든다. 보이스 스레드·메인·공지·오버레이·분석은 그대로 둔다.
3. **소스 카운터(D3)**: timescale 래퍼가 청크마다 소스 소비량을 보고 → `GuildMixer.subSourceSamples`에 누적하고,
   핸드오프 위치를 `subStartPositionMs + subSourceSamples * 1000 / SAMPLE_RATE`로 바꾼다. `subOutputSamples`(출력)는 진행도/기아 판정에 유지.
4. 시프트 생성 실패(네이티브 로드 실패) 시 fail-closed 폴백(D1 안전장치).
5. 핸드오프·취소·seek·교체·디코더 실패 시 r=1.0 리셋 + 프로세서 상태 초기화.

**P3 스파이크에서 확정해야 할 것 (검증 후 진행):**
- **보이스 스레드 스터터 확인**: 생산 측 시프트라면 보이스 스레드는 `takeSub` 복사만 한다. 보이스 스레드에 SoundTouch가 절대 안 걸리는지 코드 경로로 확인.
- **초기 지연 흡수**: `TimescalePcmAudioFilter`의 가변 출력·초기 지연(`getInitialLatencies()`)이 `prefillSubSiphon()`의 600프레임 버스트로 실제로 흡수되는지. 짧은 오버레이에서 기아가 안 나는지 실측.
- **소스 소비 보고 정확도**: `getInputOutputSampleRatio()`/`numUnprocessedSamples()`로 소스 소비를 정확히 세는지. "N/r 나누기" 근사보다 드리프트가 없는지.
- **리셋**: `seekPerformed`/`flush`/`close`가 전환 종료 시 정확히 리셋되는지.

**완료 기준:** 전환 중 박이 유지되고, 핸드오프 시점에 r=1.0으로 원속 이어받음. 기존 크로스페이드/믹서/무간격 테스트 전부 통과.

### P4 — 키 호환 (선택, 크기: S~M)

**목표:** 키가 충돌하는 쌍은 길게 겹치지 않게 한다.

**무엇을 하나:** 크로마(chroma)로 장조/단조와 키를 판정하고, 충돌하면 짧은 페이드로 폴백한다.

**완료 기준:** 키가 충돌하는 쌍은 길게 겹치지 않는다.

### 마감 — 문서·통합 테스트 (크기: S~M)

- `openapi.yaml`의 `nextTrackFirstBeatMs` → `phaseMs`로 이름 변경 및 설명 정정(현재 "first downbeat"라 잘못 적혀 있음).
- 전환 이벤트에 `beatMatched` 여부와 폴백 사유 추가.
- `README.md` 갱신.
- 합성 클릭 트랙으로 믹서 출력의 킥 정렬 오차를 재는 통합 테스트 추가.
- 기존 테스트 전부 통과 확인.

---

## 7. 데이터 모델

```kotlin
data class TrackAnalysis(
    val beat: BeatGrid?,      // 없으면 폴백
    val key: KeyEstimate?,    // P4에서만 씀, 없어도 됨
)

data class BeatGrid(
    val bpm: Float,
    val confidence: Float,    // 0..1, "주기성" 점수 (음악적 정확도 아님)
    val phaseMs: Long,        // 분석 구간에서 첫 박의 오프셋. 다운비트가 아님
)

data class KeyEstimate(
    val tonic: PitchClass,
    val mode: KeyMode,
    val confidence: Float,
)
```

정책 출력은 닫힌 결정 타입으로 표현(흩어진 boolean 금지):

```kotlin
sealed interface TransitionDecision {
    data class BeatMatched(
        val fadeBeats: Int,       // 2 | 4 | 8
        val targetBpm: Float,
        val rateRatio: Float,     // r = outgoingBpm / incomingBpm
        val incomingStartMs: Long,
    ) : TransitionDecision
    data object EqualPower : TransitionDecision   // 기존 크로스페이드
    data object Gapless : TransitionDecision       // 기존 무간격
}
```

정책은 현재/다음 곡 분석, 나가는 곡의 남은 가청 구간, AutoMix 설정을 받고 위 타입을 돌려준다. PCM 리샘플링은 정책 밖에 둔다.

---

## 8. 리스크

| 리스크 | 완화 |
|---|---|
| **박 위상은 다운비트가 아니다.** 현재 감지기는 첫 onset을 잡을 뿐. 박 격자는 바 위치/섹션을 모름 | 문서·REST에 명시. "프레이즈/다운비트 인식"이라 주장하지 않음 |
| **나가는 곡 분석이 없을 수 있다.** 현재 곡은 프리로드 시 분석 안 됐을 수 있음 | 그 경우 무조건 기존 크로스페이드/무간격. 보이스 스레드에서 동기 분석 절대 금지 |
| **두 좌표계(소스 vs 출력)가 갈라짐.** 시프트 중엔 소스 위치와 들린 위치가 다름 | D3의 `subSourceSamples`로 명시적 분리. 핸드오프는 소스, 오버레이 진행도는 출력 |
| **기존 동작이 폴백이어야 함.** 분석·DSP 실패가 재생을 멈추면 안 됨 | `BeatMatched`는 시프트 프로세서가 실제 활성화되고 계획된 r이 적용됐을 때만 보고. 네이티브 로드 실패는 fail-closed |
| **스트림·길이 미상·비시크(non-seekable) 트랙** | 분석 불가 → 무조건 폴백. 기존 `track.isSeekable` 조건이 이미 그 역할 |
| **꼬리 스캔 추가로 분석 디코딩 비용 증가** | 기존처럼 opt-in 유지 |
| **SoundTouch 초기 지연/가변 출력** | P3 스파이크에서 실측. 생산 측이므로 `prefillSubSiphon` 버스트가 흡수 |
| **보이스 스레드 스터터 (실시간 DSP 위험)** | 시프트를 생산 측(sub 체인)에 두어 보이스 스레드에 SoundTouch가 절대 안 걸리게 함(D2). P3 스파이크에서 코드 경로로 확인. 보이스 스레드는 `takeSub` 복사만 유지 |

---

## 9. 검증 전략 (요약)

- **단위**: `BpmDetectorTest`(보간/옥타브/위상), `BeatMatchPolicyTest`(경계값), `KeyDetectorTest`(P4).
- **통합**: 합성 클릭 트랙으로 킥 정렬 오차 측정, `CrossfadeMixIntegrationTest`/`MixerFilterTest`/`GuildMixerProtocolTest` 등 기존 전부 통과.
- **종단**: 로컬 WAV 클릭 트랙 2개를 서로 다른 템포로 돌려 정렬·한도·폴백·핸드오프 후 r=1.0 복귀 확인.
- **명령**: `./gradlew test` (전체). 단계별로는 `./gradlew test --tests '...'`로 해당 테스트만.
