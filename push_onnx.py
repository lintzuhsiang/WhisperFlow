import os, subprocess, sys

def run_cmd(cmd):
    print(f"Running: {cmd}")
    result = subprocess.run(cmd, shell=True)
    if result.returncode != 0:
        print(f"Error executing {cmd}")
        sys.exit(1)

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

files = [
    "encoder_model.onnx",
    "decoder_model.onnx_data",
    "decoder_with_past_model.onnx_data",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx"
]

for f in files:
    local_path = f"app/src/main/assets/nllb_onnx_output/{f}"
    tmp_path = f"/data/local/tmp/{f}"
    target = f"files/nllb_internal/{f}"
    
    print(f"\n--- Processing {f} ---")
    run_cmd(f'{ADB} push "{local_path}" "{tmp_path}"')
    run_cmd(f'{ADB} shell "run-as com.liveTranslation cp {tmp_path} {target}"')
    run_cmd(f'{ADB} shell rm "{tmp_path}"')

print("\nDone pushing files cleanly into internal app storage!")
