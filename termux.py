#!/usr/bin/env python3
"""
MyInstants Final Auto-Downloader & Organizer
Runtime: Python 3.10+
Dependencies: aiohttp, rich

FLOW:
  1. I-download lahat ng files via direct links.
  2. Gumawa ng bagong folder (MyInstants_Pinoy_Sounds).
  3. I-move/transfer lahat ng files doon.
"""

import asyncio
import aiohttp
import os
import shutil
import sys
from pathlib import Path

try:
    from rich.console import Console
    from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn, TimeElapsedColumn
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

console = Console() if RICH_AVAILABLE else print

# ==========================================
# CONFIGURATION
# ==========================================
# Final destination folder
TARGET_FOLDER = Path("/storage/emulated/0/Download/MyInstants_Pinoy_Sounds")
BASE_URL = "https://www.myinstants.com/media/sounds/"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}

# Pinagsamang listahan ng lahat ng files (PAGE 1 TO 6)
ALL_FILES = [
    # PAGE 1
    "silly-laugh_x4pBItl.mp3", "walang-kanin-buseng.mp3", "tobol.mp3", "ganda_mo_intro-mp3cut.mp3",
    "iyot-sfx.mp3", "lahat-sisimhutin-pati-bulbol-sa-otin.mp3", "putangina-sound-meme-mp4.mp3",
    "hazel-faithkinikiligofficial-mus-mp3cut.mp3", "pinoy-yapping.mp3", "bat-nilalabas-mo-tite-mo.mp3",
    "pinoy-reference-mp3cut.mp3", "dalawang-beses-nayan-filipino-meme-sound-effects.mp3",
    "uy-bagsak-tang-ina-mo-filipino-meme-sound-effects_Af8qc0g.mp3",
    "oops-tama-na-masama-nakiki-pag-away-filipino-meme-sound-effects.mp3",
    "para-lang-sayo-igiling-giling-mo_VfrsWza.mp3", "uy-boss.mp3", "i-am-the-hunter-i-am-pinoy.mp3",
    "bahog-bilat-filipino-memes-sound-effects.mp3", "sara-duterte-cursing.mp3", "gusto-ko-tt.mp3",
    "walang-aawat.mp3", "horse-race-pinoy.mp3", "pantropiko.mp3", "right-paldo-sarap.mp3",
    "piste-ka-koleraha-ka.mp3", "bang-patay-sugarat.mp3", "silly-pinoy-laugh-track.mp3",
    "beer-by-the-itchyworms.mp3", "alexis-texas-pinoy.mp3", "suyop-kada-buntag.mp3",
    "ayaw-kalimot-pautog.mp3", "globe-philippines-not-enough-load.mp3", "ngiti-mp3cut.mp3",
    "bong-go.mp3", "ice-cream-yummy-ice-cream-gud.mp3", "oskar_10UxpVW.mp3",
    
    # PAGE 2
    "pinoy-babaero.mp3", "ritemed-only-yan.mp3", "lame-kayo2.mp3", "pinoy-oh-no-krinds-meme.mp3",
    "iyot-meme-pinoy.mp3", "mali-ka-nyan-mali-ka-nyan-kapatid-filipino-meme-sound-effects.mp3",
    "sm-malls-theme-philippines.mp3", "kaba-tootsie-guevara-lyrics-mp3cut.mp3",
    "ice-cream-yummy-ice-cream-good-enhanced_gpZ9WsE.mp3", "love-joy-hope-abs-cbs_VTdq6GO.mp3",
    "eggo-commercial-jingles.mp3", "milyon-pans.mp3", "family-forget-pinoy.mp3",
    "be-sure-stop-meme-pinoy.mp3", "pinoy-binihlhan-nko-ng-relos.mp3", "explosion_YajEFFs.mp3",
    "ge-talon-una-ulo-filipino-meme-sound-effects.mp3", "jollibee-party-all.mp3",
    "lab-murag-di-lage-ka-ganahan-lab.mp3", "combo-laugh.mp3", "gerine-laugh.mp3",
    "happy-birthday-agassi.mp3", "i-grew-up-with-a-lot-of-pilipinos.mp3", "ted-ted-ted-ted.mp3",
    "pia-aha.mp3", "johoy-to-the-world.mp3", "harry-roque-tawa-1.mp3", "lamonnalamon.mp3",
    "wala-akong-roblox.mp3", "pinoy.mp3", "maja-keratin-plus-pinoy.mp3", "voila-pinoy.mp3",
    "eating-sarap.mp3", "abs-cbn-final-sign-off.mp3", "noah-ng-wala.mp3", "wala-hirambecelestre.mp3",

    # PAGE 3
    "bone-catrat.mp3", "bye-ball-khi.mp3", "you-promised-free-robux-2.mp3", "cebu-close-tayo.mp3",
    "wow-mahuasy.mp3", "good-shot-bhaiya-pinoy.mp3", "change-password-meme.mp3", "131-eight.mp3",
    "myarwenn4.mp3", "adam-j-oras.mp3", "mta-mate.mp3", "ubae-yey-dora.mp3", 
    "ji-laugh-hahahayyyyyy.mp3", "amacana-mama.mp3", "akala-mo-lang-wala-video-clip.mp3",
    "get-that-thirty-bro-viral-full-video-audiotrimmer_bhjH7Rz.mp3", "tawaby-tawaby-pinoy.mp3",
    "smah-dj-snake-pinoy.mp3", "pinoy-lanet-oraspuya-bak-gi.mp3", "hanggang-saan-pinoy.mp3",
    "bluetooh-bye-bye-pinoy.mp3", "signature-csna-pinoy.mp3", "bunot-pinoy.mp3", 
    "tj-self-gsk-pinoy.mp3", "sir-yes-sir-pinoy-viral.mp3", "sarap-pro-pinoy.mp3",
    "kim-chiu-pinoy.mp3", "current-pinoy.mp3", "cebuano-pinoy.mp3", "ikaw-lady-chice-pinoy.mp3",
    "loud-binoy-people-pinoy.mp3", "train-horn-meme-pinoy.mp3", "binoy-people-pinoy.mp3",
    "facebook-online-pinoy.mp3",

    # PAGE 4
    "selo-pinoy.mp3", "keratin-pinoy-67.mp3", "pinoy-vbb.mp3", "family-feud-binoy-pinoy-hahaha.mp3",
    "pinoy-can-you-keep-it-aside.mp3", "hira-unilab-yan-only-pinoy.mp3", "safeguard-pinoy.mp3",
    "pride-baho-bilat-pinoy.mp3", "when-the-pinoy.mp3", "galing-ng-pinoy.mp3",
    "network-disclaimer-pinoy.mp3", "pinoy-recipe.mp3", "abby-binay-pinoy.mp3", "kaba-pinoy.mp3",
    "pinoy-please-eye-pi.mp3", "balloon-bunot-pinoy.mp3", "pinoy-love-you-baby-lose-your.mp3",
    "imme-maroc-pinoy.mp3", "11-9-pinoy-rn-radio.mp3", "taph-taph-taph-filipino-meme-sound-effects.mp3",
    "binoy-may-tanong_bdWjqZR.mp3", "control-successfuly.mp3", "default-ringing-tone-vivo.mp3",
    "kampanerang-kuba.mp3", "pastilan.mp3", "web-car-well-come.mp3", "24-oras.mp3", 
    "albert-english.mp3", "sarap-pro.mp3", "world-class-uno.mp3", "gsk.mp3",
    "galing-mo-effect.mp3", "dora-we-did.mp3", "camille-laugh.mp3",
    "shoutout-teddie-caloy.mp3", "shoutout-teddie.mp3", "shoutout-caloy.mp3", "je-laugh.mp3",
    "sel-laugh.mp3", "abs-cbs-tv-plus-stand.mp3", "lintik-kayo.mp3",

    # PAGE 5
    "sinok.mp3", "manigir-kasi-aku-d2.mp3", "meme-i-created-sasuke-nokia.mp3", "binoy.mp3",
    "ryan-rems-1.mp3", "zed-load.mp3", "thepebbles-whaz-going-on.mp3", "pigrolac-tvc2006.mp3",
    "tedieeeee.mp3", "angelo-magatowtow.mp3", "ji-laugh-v3.mp3", "ji-laugh-v2.mp3",
    "lako-sa-mood_zSHJD2p.mp3", "amoy-sunog.mp3", "maingay-na-bata.mp3", "vice-ganda-text.mp3",
    "john-joseph-swearing.mp3", "mga-sheep-sheep-by-arkin-animation.mp3",
    "harry-roque-see-you-later_VQNpbK7.mp3", "harry-roque-seggs.mp3", "harry-roque-hirap-sagutin.mp3",
    "harry-roque-topless_hdFvSGT.mp3", "harry-roque-tawa-2.mp3", "daleng-dale.mp3", "wahaw.mp3",
    "kittycapiral-abaw_rv5uI9p.mp3", "chincapiral-abaw.mp3",

    # PAGE 6
    "kittycapiral-yuckie.mp3", "bakit_pa-mp3cut.mp3", "donna-cruz-isang-tanong-isang-sa.mp3",
    "kahit-takpan-mo-pa-mukha-mo.mp3"
]

# ==========================================
# LOGIC
# ==========================================

async def download_file(session, filename):
    """Download single file directly to target folder."""
    url = BASE_URL + filename
    filepath = TARGET_FOLDER / filename
    
    # Skip kung na-download na
    if filepath.exists() and filepath.stat().st_size > 0:
        return "skipped"
        
    try:
        async with session.get(url, headers=HEADERS, timeout=20) as resp:
            if resp.status == 200:
                with open(filepath, 'wb') as f:
                    while True:
                        chunk = await resp.content.read(8192)
                        if not chunk:
                            break
                        f.write(chunk)
                return "success"
            else:
                return f"error ({resp.status})"
    except Exception as e:
        return f"error ({str(e)[:20]})"

async def main():
    print(f"[*] Target Folder: {TARGET_FOLDER}")
    
    # 1. Create Folder
    try:
        TARGET_FOLDER.mkdir(parents=True, exist_ok=True)
        print(f"[✓] Folder created/verified.")
    except PermissionError:
        print("[✗] Permission denied. Run 'termux-setup-storage' first.")
        return

    print(f"[*] Starting download of {len(ALL_FILES)} files...\n")

    async with aiohttp.ClientSession() as session:
        if RICH_AVAILABLE:
            with Progress(
                SpinnerColumn(),
                TextColumn("[progress.description]{task.description}"),
                BarColumn(),
                TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
                TimeElapsedColumn(),
            ) as progress:
                task = progress.add_task("[cyan]Downloading...", total=len(ALL_FILES))
                
                for filename in ALL_FILES:
                    progress.update(task, description=f"[cyan]{filename[:25]}...")
                    status = await download_file(session, filename)
                    
                    if status == "success":
                        print(f"  [✓] Downloaded: {filename}")
                    elif status == "skipped":
                        pass # Hindi na natin i-print para hindi mag-spam
                    else:
                        print(f"  [✗] Failed: {filename} - {status}")
                        
                    progress.advance(task)
                    await asyncio.sleep(0.3) # iwas 429
        else:
            for filename in ALL_FILES:
                status = await download_file(session, filename)
                if status == "success":
                    print(f"[✓] {filename}")
                elif status != "skipped":
                    print(f"[✗] {filename} - {status}")
                await asyncio.sleep(0.3)

    print(f"\n[✓] Done! Lahat ng files ay nasa: {TARGET_FOLDER}")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[yellow]Aborted.[/yellow]")
