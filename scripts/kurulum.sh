#!/bin/zsh
# Projenin dış bağımlılıklarını hazırlar: whisper.cpp ve llama.cpp kaynak kodu (sabit sürümler).
set -e
cd "$(dirname "$0")/.."
WHISPER_COMMIT=6e4ab854f67f743900934a703d5603419384c961
if [ ! -d third_party/whisper.cpp ]; then
  git clone https://github.com/ggml-org/whisper.cpp.git third_party/whisper.cpp
fi
git -C third_party/whisper.cpp fetch --depth 1 origin $WHISPER_COMMIT 2>/dev/null || true
git -C third_party/whisper.cpp checkout -q $WHISPER_COMMIT
# Yama: dil tespitinde kodlanan ses yazıya dökmede yeniden kodlanmasın (bkz. yama dosyasının başı).
PATCH="$PWD/scripts/whisper-kodlama-paylasimi.patch"
if git -C third_party/whisper.cpp apply --reverse --check "$PATCH" 2>/dev/null; then
  echo "whisper.cpp yaması zaten uygulanmış"
else
  git -C third_party/whisper.cpp apply "$PATCH"
  echo "whisper.cpp yaması uygulandı"
fi
echo "whisper.cpp hazır: $(git -C third_party/whisper.cpp log -1 --format='%h %cs')"

LLAMA_COMMIT=25747b08e7a0f9a59a2089ce6b98d2229b76042a
if [ ! -d third_party/llama.cpp ]; then
  git clone https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
fi
git -C third_party/llama.cpp fetch --depth 1 origin $LLAMA_COMMIT 2>/dev/null || true
git -C third_party/llama.cpp checkout -q $LLAMA_COMMIT
echo "llama.cpp hazır: $(git -C third_party/llama.cpp log -1 --format='%h %cs')"
