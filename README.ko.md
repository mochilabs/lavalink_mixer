# lavalink_mixer

[English](README.md) | [한국어](README.ko.md)

[![Release](https://img.shields.io/github/v/release/mochilabs/lavalink_mixer?color=blue&label=release)](https://github.com/mochilabs/lavalink_mixer/releases/latest)
[![License](https://img.shields.io/badge/license-Unlicense-green)](UNLICENSE)
[![Lavalink](https://img.shields.io/badge/Lavalink-v4-orange)](https://github.com/lavalink-devs/Lavalink)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net)

DJ 스타일 오디오 믹싱, 스마트 트랜지션, 오디오 보정을 서버 사이드에서 실시간으로 처리하는 고성능 [Lavalink v4](https://github.com/lavalink-devs/Lavalink) 플러그인입니다.

- 🎧 **비트 매칭 오토믹스 (Beat-Matched AutoMix)**: 이전 곡과 다음 곡의 비트(2/4/8박자)를 정밀하게 맞추고, 필요시 템포를 임시 조절(Timescale)한 뒤 자연스럽게 복귀.
- 🎚️ **이퀄 파워 크로스페이드 (Equal-Power Crossfade)**: 소프트 니(Soft-knee) 리미팅으로 두 곡이 겹치는 구간의 디지털 클리핑(깨짐) 방지.
- 🔇 **스마트 무음 스킵 (Silence Skipping)**: 백그라운드 사전 분석 및 실시간 PCM 감지로 곡 앞뒤의 불필요한 공백을 자동 제거.
- 🔊 **EBU R128 음량 정규화**: ITU-R BS.1770-4 K-weighting 이중 게이팅 기반 -14.0 LUFS 표준 음량 자동 보정.
- 🗣️ **TTS / 공지 오버레이 (Ducking)**: 음악 재생 중 안내 음성이 나오면 배경 음악 볼륨을 자동으로 낮추고 복원 (순차/병렬 오버레이 지원).
- ⚡ **JIT 에포크 펜싱 큐 프로토콜 (v2)**: 멱등성 키와 단조 증가 에포크를 사용하여 빠른 스킵/큐 조작 시 발생하는 레이스 컨디션 및 중복 재생 완벽 방지.

**Lavalink 소스 코드 수정이 필요 없습니다.** 순수 Kotlin/JVM으로 구현되어 외부 네이티브 DSP나 별도 FFT 라이브러리 설치가 필요하지 않습니다.

---

## 관련 문서

- 📋 **OpenAPI REST 명세서**: [`api/openapi.yaml`](api/openapi.yaml)
- 🤖 **봇 연동 가이드**: [`examples/lavalink-client/README.md`](examples/lavalink-client/README.md)
- 🏗️ **아키텍처 및 내부 구조**: [`docs/architecture.md`](docs/architecture.md)
- 🛠️ **빌드 및 테스트 가이드**: [`docs/development.md`](docs/development.md)
- 📊 **오토믹스 트랜지션 캘리브레이션**: [`docs/adaptive-calibration.md`](docs/adaptive-calibration.md)

---

## 빠른 시작 (Quickstart)

### 1. 플러그인 설치

Lavalink의 `application.yml` 파일에 플러그인 의존성을 추가합니다:

```yaml
lavalink:
  plugins:
    - dependency: "io.github.mochilabs.mixer:mixer-plugin:0.3.6"
      repository: "https://maven.pkg.github.com/mochilabs/lavalink_mixer"
```

또는 [Releases](https://github.com/mochilabs/lavalink_mixer/releases)에서 `lavalink_mixer-0.3.6.jar`를 다운로드하여 Lavalink의 `plugins/` 디렉터리에 넣고 Lavalink를 재시작합니다.

### 2. 필터 활성화 (길드별 1회 필수)

Lavalink 필터는 플레이어 단위로 동작합니다. 음악 재생 **전** 또는 재생 중에 Lavalink `filters` 요청을 보냅니다:

```json
{
  "op": "filters",
  "guildId": "123456789012345678",
  "pluginFilters": {
    "mixer": {
      "guildId": "123456789012345678"
    }
  }
}
```

> ⚠️ **주의**: `guildId`는 반드시 **문자열(String)**로 전송해야 합니다. Discord Snowflake ID는 64비트 정수로, JavaScript의 기본 53비트 정수 범위를 초과합니다.

---

## 기본 설정 (`application.yml`)

Lavalink `application.yml`의 `mixer:` 블록에 기본값을 설정할 수 있습니다 (REST API를 통해 길드별로 실시간 변경 가능):

```yaml
mixer:
  # 크로스페이드 및 페이드인
  crossfadeEnabled: true
  crossfadeMs: 5000               # 겹침 시간(ms) (500 .. 30000)
  fadeInMs: 300                   # 곡 시작 시 페이드인 시간(ms) (0 = 끔)

  # 공지사항 오버레이 덕킹 (Ducking)
  duckLevel: 0.2                  # 공지 재생 중 배경 음악 볼륨 배율 (0.0 .. 1.0)
  duckFadeMs: 300                 # 볼륨 감소/복원 램프 시간(ms)
  maxParallelOverlays: 4          # 동시 병렬 TTS 오버레이 최대 개수

  # 음량 정규화 (EBU R128 / ITU-R BS.1770-4)
  normalizationEnabled: false     # 활성화 시 사전 디코딩 분석 수행
  targetLufs: -14.0               # 표준 목표 음량 (-30.0 .. -6.0 LUFS)

  # 무음 건너뛰기
  silenceSkipEnabled: false       # 곡 앞뒤 무음 자동 트림
  silenceThresholdDb: -60.0       # 무음 판정 기준 음량(dB)
  silenceMinSoundMs: 120          # 유음 확인 최소 지속 시간(ms)
  silenceHeadScanMs: 15000        # 곡 시작 구간 분석 윈도우(ms)
  silenceTailScanMs: 15000        # 곡 끝 구간 분석 윈도우(ms)
  tailConfirmMs: 1000             # 실시간 스트림 무음 조기 컷 확정 대기 시간(ms)

  # 비트 매칭 오토믹스 및 BPM 분석
  bpmDetectionEnabled: false      # 템포 및 비트 그리드 분석 활성화
  bpmScanMs: 30000                # 헤드/테일 분석 윈도우(ms) (5000 .. 60000)
  beatmatchEnabled: false         # 비트 정렬 및 템포 조절 활성화
  maxTempoChange: 0.06            # 최대 허용 템포 변경 비율 (기본 6%, 최대 12%)
  beatmatchMinConfidence: 0.30    # 비트 그리드 수용 최소 신뢰도

  # 백그라운드 워커
  analysisThreads: 2              # 트랙 사전 분석 병렬 워커 스레드 수
```

---

## 핵심 기능 상세

### 1. 비트 매칭 오토믹스 (Beat-Matched AutoMix)
순수 Kotlin 고정밀 분석 엔진을 통해 독립된 백그라운드에서 분석을 수행합니다:
- **Radix-2 Real FFT (N=2048, Hann Window)**: 힙 메모리 할당 없이 PCM을 10ms 홉 단위로 주파수 변환.
  - 다중 채널 독립 FFT: 채널별로 FFT를 수행한 후 로그 크기($L_t(k) = \ln(1 + 10 \cdot |X_t(k)|)$)를 평균하여 위상 상쇄 방지.
  - 가변 샘플레이트 지원: 48kHz, 44.1kHz 등 입력 샘플레이트에 맞춰 홉 크기(~10ms)와 주파수 대역 빈 경계를 동적 계산.
  - 대역별 표준편차 정규화: 저음역 킥/베이스 에너지가 고음역 스네어/하이햇 어택을 가리지 않도록 대역별 표준편차($SF_{\text{band}} / \sigma_{\text{band}}$)로 균등 가중합.
  - **Low (30–150 Hz)**: 킥 드럼 및 서브베이스 어택 분리
  - **Mid (150–2,000 Hz)**: 스네어, 클랩, 악기 어택 분리
  - **High (2,000–10,000 Hz)**: 하이햇, 심벌, 셰이커 분리

$$
SF_{\text{band}}(t) = \sum_{k \in \text{band}} \max\left(0, L_t(k) - L_{t-1}(k)\right)
$$

- **정규화 자기상관 및 옥타브 판별**: 윈도우 길이 편향 없는 60–200 BPM 래그 탐색. 온셋 간격의 5% 상대오차(최소 1.5홉) 및 4개 이상 일치 검증, 8분음표 하이햇 세분화 교차 검증($2.2\times$ 에너지 비율 기준) 적용. 간격이 없을 경우 최대 신뢰도 대비 0.35 이내이면서 $\ge 0.35$인 빠른 템포 우선 채택.
- **전역 위상 및 가중 최소제곱(WLS) 주기 최적화**: 코사인 펄스 커널($tolerance = \max(1.5\text{ hops}, T \times 0.10)$, $dist < tolerance$ 가드)로 최적 위상을 탐색한 뒤, 가중 최소제곱 회귀를 통해 주기 $T$와 위상 $\phi$를 동시 정밀화하여 30초 테일 구간 누적 드리프트 제거:

$$
K(d, tol) = \cos^2\left(\frac{\pi \cdot d}{2 \cdot tol}\right) \quad (d < tol), \quad 0 \quad (\text{otherwise})
$$
- **경계 무음 자동 트림 결합**: 인코딩 딜레이나 앞뒤 무음이 있어도 무음을 건너뛴 실제 음악 시작 지점부터 2/4/8박자 비트 매칭 수행.
- **템포 스트레칭 및 복구**: 두 곡의 템포가 `maxTempoChange` 이내로 다를 경우(예: 124 BPM → 126 BPM), 크로스페이드 구간 동안 들어오는 곡의 속도를 임시 조절하고, 핸드오프가 완료되면 원래 1.0 템포로 점진적 복귀.

### 2. 이퀄 파워 크로스페이드 (소프트 니 리미팅)
두 개의 소리가 겹치면 최대 $+3\text{ dB}$ ($1.41\times$)까지 신호가 증폭됩니다:
- 이퀄 파워 게인 곡선:

$$
g_{\text{out}}^2 + g_{\text{in}}^2 = 1
$$

- 합산 신호가 `0.9`를 초과하면 소프트 니(Soft-knee) 압축 곡선을 적용해 `1.0` 아래로 완만하게 리미팅하여 찌그러짐(스퀘어파 왜곡) 없이 자연스러운 다이내믹을 유지합니다. A $1.41\times$ 피크는 $\approx 0.98$ (청감상 약 $\approx 0.14\text{ dB}$ 압축)에 안착합니다.

### 3. 스마트 무음 스킵
- **사전 분석 패스**: 곡 시작점의 인코딩 지연/무음과 곡 끝점의 페이드아웃 후 무음을 백그라운드에서 정확히 측정하여 건너뜁니다.
- **실시간 PCM 감지**: 라이브 스트림이나 분석 불가 트랙의 경우, 믹서 필터의 실시간 오디오 레벨이 `tailConfirmMs` 동안 무음으로 유지되면 곡 끝까지 대기하지 않고 다음 곡으로 즉시 전환합니다.

### 4. EBU R128 음량 정규화
- ITU-R BS.1770-4 K-weighting 필터와 400ms 블록(75% 오버랩), 이중 게이팅($-70\text{ LUFS}$ 절대 게이트, $-10\text{ LU}$ 상대 게이트)을 사용해 통합 음량을 측정합니다.
- 목표 음량(`targetLufs`, 기본 $-14.0\text{ LUFS}$)에 맞춰 디지털 선형 게인을 적용합니다:

$$
G = \min\left(1.0, 10^{\frac{\text{targetLufs} - \text{measuredLufs}}{20}}\right)
$$

- 음량이 과도하게 작은 트랙이 디지털 클리핑을 일으키지 않도록 최대 게인은 `MAX_GAIN = 1.0`으로 안전하게 제한됩니다.

### 5. TTS / 안내 방송 오버레이
- 음악 재생을 멈추지 않고 효과음이나 TTS 안내를 동시에 믹싱합니다.
- **덕킹(Ducking)**: 안내 방송이 시작되면 음악 볼륨을 `duckLevel`(예: 20%)로 부드럽게 낮추고, 방송이 끝나면 원래 볼륨으로 복원합니다.
- `sequential`(순차 재생) 및 `parallel`(동시 병렬 재생) 모드를 지원합니다.

---

## 큐 프로토콜 v2 (JIT 프리페치 및 에포크 펜싱)

자연스러운 크로스페이드를 위해 서버는 현재 곡이 끝나기 전에 다음 곡 정보를 미리 요청합니다:

```text
[곡 A 재생 중]
        │
        ▼ (남은 재생 시간이 crossfadeMs + 7000ms 에 도달)
서버가 WebSocket 이벤트 발송: MixerNeedNextTrackEvent
        │
        ▼
클라이언트가 REST 요청 전송: POST /mixer/queue/arm
(Body: { guildId, epoch, encodedTrack }, Header: Idempotency-Key)
        │
        ▼ (서버가 백그라운드에서 곡 B 디코딩 및 BPM/무음 분석 완료)
서버가 전환 계획 수립 (BeatMatched / Crossfade / SilenceTrim)
        │
        ▼ (핸드오프 시작 지점 도달)
서버가 서브 플레이어로 곡 B 재생 시작 -> MixerFilter에서 두 곡 믹싱
서버가 WebSocket 이벤트 발송: overlapStart
        │
        ▼ (크로스페이드 완료)
서버가 곡 B를 메인 트랙으로 승격 -> WebSocket 이벤트 발송: MixerAdvancedEvent
클라이언트 로컬 큐 동기화 (클라이언트는 autoSkip: false 유지)
```

- **단조 증가 에포크(Epoch)**: 지연된 이전 요청이 뒤늦게 도착하더라도 에포크 검증을 통해 `HTTP 409 Conflict`로 안전하게 무시합니다.
- **멱등성 키(Idempotency-Key)**: 네트워크 재시도 시 동일 키에 대해 중복 연산 없이 안전하게 결과를 반환합니다.
- **클라이언트 강제 오버라이드**: 사용자가 즉시 스킵(`PLAY_NOW`)하거나 정지(`STOP`)하면 진행 중이던 크로스페이드 상태를 즉시 무효화하고 즉각 전환합니다.

---

## REST API 레퍼런스

모든 요청은 표준 Lavalink `Authorization` 헤더를 포함해야 합니다.

### 큐 및 재생 제어

| 메서드 | 경로 | 헤더 | 바디 | 설명 |
|---|---|---|---|---|
| `GET` | `/mixer/info` | — | — | 플러그인 정보 반환 `{ protocolVersion: 2, version: "0.3.6" }` |
| `GET` | `/mixer/state?guildId=` | — | — | 믹서 상태, 현재 설정, 전환 판정 이유, BPM 조회 |
| `POST` | `/mixer/analyze` | — | `{ guildId, encodedTrack }` | 다음 후보곡 사전 분석 캐싱 요청 (202 반환) |
| `POST` | `/mixer/queue/arm` | `Idempotency-Key` | `{ guildId, epoch, encodedTrack?, isEmpty, preserveTransition? }` | 다음 곡 장전 (JIT 전환 준비) |
| `POST` | `/mixer/queue/invalidate` | `Idempotency-Key` | `{ guildId, epoch }` | 장전된 곡 취소 (`MixerNeedNextTrackEvent` 재발생) |
| `POST` | `/mixer/queue/override` | `Idempotency-Key` | `{ guildId, epoch, action, encodedTrack? }` | 강제 전환 (`action: "STOP"` 또는 `"PLAY_NOW"`) |
| `POST` | `/mixer/reset` | `Idempotency-Key` | `{ guildId, epoch, windowOpenGraceMs? }` | 믹서 상태 머신 리셋 및 에포크 동기화 |

### DSP 및 전환 설정

| 메서드 | 경로 | 바디 파라미터 | 설명 |
|---|---|---|---|
| `POST` | `/mixer/crossfade` | `{ guildId, enabled, durationMs? }` | 크로스페이드 설정 (500–30000ms) |
| `POST` | `/mixer/fade-in` | `{ guildId, durationMs }` | 시작 페이드인 시간 설정 (0–10000ms) |
| `POST` | `/mixer/bpm` | `{ guildId, enabled, scanMs?, beatmatchEnabled?, maxTempoChange?, minConfidence? }` | BPM 분석 및 오토믹스 전체 설정 |
| `POST` | `/mixer/beatmatch` | `{ guildId, enabled, maxTempoChange?, minConfidence? }` | 비트 매칭 기능만 독립 토글 |
| `POST` | `/mixer/silence` | `{ guildId, enabled, thresholdDb?, minSoundMs?, headScanMs?, tailScanMs?, confirmMs? }` | 무음 건너뛰기 설정 |
| `POST` | `/mixer/normalization` | `{ guildId, enabled, targetLufs? }` | EBU R128 음량 정규화 설정 |

### 오버레이 및 공지사항

| 메서드 | 경로 | 바디 파라미터 | 설명 |
|---|---|---|---|
| `POST` | `/mixer/announce` | `{ guildId, identifier?, encodedTrack?, duckLevel?, mode?, channel? }` | 안내 음성 재생 (`mode`: `sequential` \| `parallel`, `channel`: `both` \| `left` \| `right`) |
| `POST` | `/mixer/announce/cancel` | `{ guildId }` | 현재 공지 즉시 중단 및 음악 볼륨 복원 |
| `DELETE` | `/mixer/announce/queue` | 쿼리: `?guildId=` | 대기 중인 모든 공지 취소 |

---

## WebSocket 이벤트 명세 (`op: "mixer"`)

Lavalink 웹소켓 클라이언트로 전달되는 이벤트 예시입니다:

```jsonc
// 1. 다음 곡 장전 필요 (JIT 창 도달)
{ "op": "mixer", "type": "MixerNeedNextTrackEvent", "guildId": "123", "remainingMs": 12000, "currentTrackEncoded": "..." }

// 2. 크로스페이드 믹싱 시작
{ "op": "mixer", "type": "overlapStart", "guildId": "123", "crossfadeMs": 4000, "handoffInMs": 4000, "beatMatched": true, "fadeBeats": 8, "targetBpm": 124.0, "rateRatio": 1.016 }

// 3. 서버 측 재생 진행 완료 (클라이언트 큐 동기화 필요)
{ "op": "mixer", "type": "MixerAdvancedEvent", "guildId": "123", "epoch": 2, "encodedTrack": "...", "handoffType": "CROSSFADE", "handoffId": "uuid" }

// 4. 크로스페이드 완료
{ "op": "mixer", "type": "overlapEnd", "guildId": "123", "reason": "handoff", "beatMatched": true }

// 5. 유저 오버라이드 실행 완료
{ "op": "mixer", "type": "MixerOverriddenEvent", "guildId": "123", "epoch": 3, "requestId": "key", "action": "PLAY_NOW" }
```

---

## REST로 오토믹스 활성화 및 테스트

실행 중인 Lavalink 서버에서 비트 매칭 오토믹스를 활성화하는 방법:

```http
POST /mixer/crossfade
Content-Type: application/json

{
  "guildId": "123456789012345678",
  "enabled": true,
  "durationMs": 5000
}
```

```http
POST /mixer/bpm
Content-Type: application/json

{
  "guildId": "123456789012345678",
  "enabled": true,
  "beatmatchEnabled": true,
  "scanMs": 30000,
  "maxTempoChange": 0.06,
  "minConfidence": 0.30
}
```

전환 결정 결과는 언제든 다음 요청으로 확인할 수 있습니다:
```http
GET /mixer/state?guildId=123456789012345678
```
- `"transitionKind": "beat_matched"` → 비트 매칭 오토믹스 정상 수행.
- `"transitionKind": "short_crossfade"` → 이퀄 파워 크로스페이드로 전환 (예: BPM 차이가 6%를 초과하거나 조성이 충돌한 경우).

---

## 아키텍처 및 오디오 흐름

Discord 음성 채널은 연결당 단 하나의 Opus 스트림만 수용하므로, 두 개의 소리는 **인코딩 전 PCM 단계**에서 섞여야 합니다:

```text
[메인 플레이어] ──> [메인 디코더] ──> [MixerFilter] ──> [볼륨 / EQ 필터] ──> [Opus 인코더] ──> [Discord 음성]
                                           ▲
                                           │ (PCM 합성: main * mainGain + sub * subGain)
[서브 플레이어] ──> [서브 디코더] ──> [SiphonQueue]
(다음 곡 / TTS)
```

1. **사이폰 필터(Siphon Filter)**: 독립된 서브 플레이어가 음성 연결 없이 백그라운드에서 다음 곡/TTS를 디코딩하여 링 버퍼에 저장합니다.
2. **믹서 필터(MixerFilter)**: 메인 플레이어 필터 체인의 최전단에 위치하여, 디코딩된 PCM 청크를 직접 합성합니다.
3. **단일 큐 엔진**: 재생/정지(`play()`/`stop()`) 등의 상태 변경은 이벤트 큐를 통해 단일 스레드로 처리되어 음성 스트리밍 스레드에 락 경합이나 데드락을 유발하지 않습니다.

---

## 소스 코드 빌드

요구 사항: **JDK 17 이상**

```bash
# 플러그인 JAR 빌드
./gradlew jar

# 전체 단위 및 오디오 통합 테스트 실행
./gradlew test

# 배포용 아티팩트 빌드
./gradlew build
```

빌드 결과물: `build/libs/lavalink_mixer-0.3.6.jar`.

---

## 라이선스

이 프로젝트는 퍼블릭 도메인 [Unlicense](UNLICENSE)에 따라 자유롭게 사용할 수 있습니다.
