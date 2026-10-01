#!/bin/zsh
# macOS seslendirmesiyle 19 dilde test cümleleri üretir (16 kHz mono WAV) ve telefona gönderir.
# Kullanım: tools/test-sesleri.sh [adb-seri-no]
set -e
OUT="$(dirname "$0")/../../canli-ceviri-deps/test-audio"
mkdir -p "$OUT"
MANIFEST="$OUT/manifest.tsv"
: > "$MANIFEST"
ekle() { # dosya dil ses metin
  local f=$1 lang=$2 voice=$3 text=$4
  say -v "$voice" -o "$OUT/$f.aiff" "$text"
  afconvert -f WAVE -d LEI16@16000 -c 1 "$OUT/$f.aiff" "$OUT/$f.wav"
  rm -f "$OUT/$f.aiff"
  printf "%s\t%s\t%s\n" "$f.wav" "$lang" "$text" >> "$MANIFEST"
}
ekle tr1 tr Yelda "Merhaba, pasaport kontrolü nerede acaba?"
ekle tr2 tr Yelda "Uçağım üçte kalkıyor ama bagajımı kaybettim, bana yardım eder misiniz?"
ekle tr3 tr Yelda "Otele gitmek istiyorum, taksi ne kadar tutar?"
ekle tr4 tr Yelda "Eczane nerede, başım çok ağrıyor ve ilaç almam gerekiyor."
ekle tr5 tr Yelda "Bu ceketin iki numara büyüğü var mı, kredi kartıyla ödeyeceğim."
ekle tr6 tr Yelda "Tren istasyonuna nasıl giderim, yürüyerek kaç dakika sürer?"
ekle tr7 tr Yelda "Rezervasyonum yarın akşam için ama bugün gelmek zorunda kaldım."
ekle tr8 tr Yelda "Cüzdanımı çaldırdım, en yakın karakol nerede?"
ekle en  en Samantha "Good morning, may I see your passport and boarding pass, please?"
ekle zh  zh Tingting "你好，请出示您的护照和登机牌。"
ekle hi  hi Lekha "नमस्ते, क्या मैं आपका पासपोर्ट देख सकता हूँ?"
ekle es  es Mónica "Buenos días, ¿puedo ver su pasaporte, por favor?"
ekle ar  ar Majed "مرحبا، هل يمكنني رؤية جواز سفرك من فضلك؟"
ekle fr  fr Thomas "Bonjour, puis-je voir votre passeport, s'il vous plaît ?"
ekle bn  bn Piya "নমস্কার, আমি কি আপনার পাসপোর্ট দেখতে পারি?"
ekle pt  pt Joana "Bom dia, posso ver o seu passaporte, por favor?"
ekle ru  ru Milena "Здравствуйте, можно ваш паспорт, пожалуйста?"
ekle id  id Damayanti "Selamat pagi, boleh saya lihat paspor Anda?"
ekle de  de Anna "Guten Tag, darf ich bitte Ihren Reisepass und Ihre Bordkarte sehen?"
ekle it  it Alice "Buongiorno, posso vedere il suo passaporto, per favore?"
ekle nl  nl Xander "Goedemorgen, mag ik uw paspoort zien, alstublieft?"
ekle pl  pl Zosia "Dzień dobry, czy mogę zobaczyć pański paszport?"
ekle cs  cs Zuzana "Dobrý den, mohu vidět váš pas, prosím?"
ekle uk  uk Lesya "Доброго дня, покажіть, будь ласка, ваш паспорт."
ekle ja  ja Kyoko "おはようございます。パスポートを見せていただけますか？"
ekle ko  ko Yuna "안녕하세요, 여권을 보여 주시겠어요?"
echo "Üretildi: $(ls "$OUT"/*.wav | wc -l | tr -d ' ') dosya → $OUT"
if [ -n "$1" ]; then
  D=/sdcard/Android/data/com.alterra.canliceviri/files/test
  adb -s "$1" shell mkdir -p $D
  adb -s "$1" push "$OUT"/*.wav "$MANIFEST" $D/ | tail -1
fi
