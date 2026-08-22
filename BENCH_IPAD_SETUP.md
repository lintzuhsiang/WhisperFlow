# iPad M5 Whisper Benchmark — Setup Guide

跑 whisper 在 iPad Pro (M5) 上的批次測試，輸出格式跟 Intel + Android 相容，
最後用同一支 `compute_cer.py` 合併分析。

---

## 1. Build whisper xcframework

```bash
cd "/Users/shinelin/Desktop/WorkSpace/Mobile Live Tranlation/whisper.cpp"
./build-xcframework.sh
```

這會產出 `build-apple/whisper.xcframework`，whisper.objc 已經 link 到它了。
M chip 會自動啟用 Metal/ANE。第一次跑大概 5–10 分鐘。

---

## 2. 開 Xcode 專案

```bash
open "examples/whisper.objc/whisper.objc.xcodeproj"
```

### 2a. 設 Signing Team（免費 Apple ID 即可）
- 點左側 `whisper.objc` 專案 → TARGETS → `whisper.objc`
- **Signing & Capabilities** tab
- **Team**: 選你自己的 Apple ID（沒有的話點 Add Account…）
- Bundle Identifier 改成獨特名稱，例如 `com.yourname.whisper.bench`
  （免費 cert 不能用別人的 bundle id）

### 2b. 把 .wav 測試音檔加進 bundle
1. Finder 開到 `bench_test_audio/`
2. 全部 `.wav` 拖到 Xcode 左側 `whisper.objc` group
3. 跳出對話框 → **勾 "Copy items if needed"** → **勾 target whisper.objc** → Finish

> ⚠ 音檔規格：16kHz / mono / 16-bit PCM。bench loader 會跳過格式不符的。

### 2c. 接 iPad Pro，選為 build target
- Xcode 上方裝置選單選 **你的 iPad Pro (M5)**
- 第一次接 iPad，要在 iPad 上：**設定 → 一般 → VPN與裝置管理** → 信任你的開發者憑證

### 2d. Build & Run
按 ⌘R。App 會裝到 iPad，自動開啟。
畫面上方會看到藍色的 **"Run Bench"** 按鈕，下面是 `Bench: idle` 狀態列。

---

## 3. 把 5 個模型搬到 iPad

App 從 `Documents/models/` 讀模型。免費 cert 沒有 Network entitlement，
最簡單是**用 Finder File Sharing**（已經在 Info.plist 加了 `UIFileSharingEnabled`）：

1. iPad 用 USB 接 Mac
2. Finder 左側點到你的 iPad → 上方分頁 **"Files"**
3. 看到 `whisper.objc`（或你 bundle 的顯示名稱）
4. 把這 5 個檔案拖進去：
   ```
   app/ggml-base-q8_0.bin
   app/ggml-small-q8_0.bin
   app/ggml-medium-q8_0.bin
   whisper.cpp/models/ggml-medium.bin
   whisper.cpp/models/ggml-large-v3-q5_0.bin
   ```
5. App 內這些會出現在 `Documents/`，但 bench code 期待在 `Documents/models/`

> 簡化做法：拖進 Finder File Sharing 後，bench code 找不到會在 log 印
> `BENCH: skip <label> — model missing`，CSV 會記 SKIP-NO-MODEL。
>
> 要把它們搬到 `models/` 子資料夾，最簡單是改 code 直接從 Documents 讀。
> 如果要改：把 ViewController.m 裡的
> ```objc
> NSString *modelsDir = [docs stringByAppendingPathComponent:@"models"];
> ```
> 改成
> ```objc
> NSString *modelsDir = docs;
> ```
> 重 build。

**或者**用 iPad **檔案 app**（Files.app）：
- 左側 → 瀏覽 → 「我的 iPad」 → `whisper.objc`
- 新建資料夾 `models`，把 5 個 .bin 拖進去

5GB 資料用 USB 走大概 1–2 分鐘。

---

## 4. 跑 Bench

1. App 內點 **"Run Bench"**
2. 狀態列會顯示 `[1/15] base-q8 × clip01 (audio=12.3s)` 之類的進度
3. 跑完顯示 `Bench done → /var/.../Documents/bench_results/results.csv`

預計時間：5 模型 × 3 clips ≈ 15 runs。M5 ANE 每個 run 0.1–2 秒，總共 1–2 分鐘。

---

## 5. 把結果撈回 Mac

Finder File Sharing：
1. iPad 接 Mac → Finder → iPad → Files → `whisper.objc`
2. 把 `bench_results/` 資料夾拖回 Mac，例如放到專案根目錄改名為 `bench_results_ipad/`

或者用 iPad 檔案 app：
- 「我的 iPad」 → `whisper.objc` → `bench_results/` → 長按 → 拷貝
- AirDrop 給 Mac

---

## 6. 算 CER

```bash
cd "/Users/shinelin/Desktop/WorkSpace/Mobile Live Tranlation"
python3 compute_cer.py bench_results_ipad/results.csv
```

會產 `bench_results_ipad/results_with_cer.csv` 跟印出 per-model average RTF + CER。

---

## 7. 三平台合併比較

三組 results.csv schema 完全相同：
```
model,clip,threads,audio_sec,wall_sec,rtf,total_time_ms,transcript
```

合併分析的話可以用 pandas：
```python
import pandas as pd
mac = pd.read_csv("bench_results/results_with_cer.csv")
android = pd.read_csv("bench_results_android/results_with_cer.csv")
ipad = pd.read_csv("bench_results_ipad/results_with_cer.csv")
mac["platform"] = "intel-mbp"
android["platform"] = "android-s24u"
ipad["platform"] = "ipad-m5"
df = pd.concat([mac, android, ipad])
print(df.groupby(["platform", "model"])[["rtf", "cer"]].mean().round(3))
```

---

## Troubleshooting

**"BENCH: no .wav in bundle"**
- 沒把 .wav 拖進 Xcode 的 Resources，或拖的時候沒勾 target

**"BENCH: skip <label> — model missing"**
- 模型沒搬到 `Documents/models/`，看 step 3
- 或者改 code 從 Documents 直接讀（step 3 註記）

**"Failed to load model" + crash**
- 模型檔案損毀（adb push / Finder 中斷）
- 重新拷貝

**"unsupported format"**
- WAV 不是 16kHz/mono/16-bit。轉檔：
  `ffmpeg -i in.xxx -ar 16000 -ac 1 -c:a pcm_s16le out.wav`

**RTF 看起來太好（< 0.05）**
- ANE 的 first-run cold start 沒有計入。如果想穩定數據，每個 (model, clip) 跑兩次取第二次。
- 目前 bench code 只跑一次每組。要跑多次的話改 `runBenchmark` 的內層迴圈。

**Xcode "Could not launch — operation couldn't be completed"**
- 第一次跑要在 iPad 上信任憑證：設定 → 一般 → VPN與裝置管理 → 信任
- 免費 cert 7 天到期，到期後重新 build & install

---

## Why this matters

S24 Ultra 跑 whisper-medium RTF ≈ 5x，根本無法即時。
M5 ANE 預期 RTF < 1（甚至 < 0.3），這樣即時翻譯才有意義。
這份 bench 拿到的數據會直接決定：

- 如果 M5 medium RTF < 0.5 → 即時翻譯可行，方向應該轉 iOS app
- 如果 M5 small RTF < 0.3、CER 還能用 → Android 改用 small + 容忍精度損失
- 如果連 M5 medium 也吃力 → 走 offline preprocess 路線
