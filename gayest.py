#!/usr/bin/env python3
import asyncio
import aiohttp
import os
import random
import re
import sys
import json
from pathlib import Path
from urllib.parse import quote
from bs4 import BeautifulSoup

# Try importing curl_cffi for advanced TLS fingerprinting (WAF Bypass)
try:
    from curl_cffi.requests import AsyncSession
    CURL_CFFI_AVAILABLE = True
except ImportError:
    CURL_CFFI_AVAILABLE = False

try:
    from rich.console import Console
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

console = Console() if RICH_AVAILABLE else print

TARGET_QUERY = "pinoy"
OUTPUT_DIR = Path("/storage/emulated/0/Download/pinoy_sounds")
MAX_PAGES = 50
CONCURRENT_REQUESTS = 3
TIMEOUT = 20

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
}

def parse_sounds(html):
    if not html:
        return []
    
    soup = BeautifulSoup(html, 'html.parser')
    sounds = []
    
    # Mas pinatibay na parser: hahanapin lahat ng button na may onclick
    buttons = soup.find_all('button', class_='small-button')
    
    for btn in buttons:
        onclick_text = btn.get('onclick', '')
        match = re.search(r"play\('([^']+)'\)", onclick_text)
        if match:
            mp3_path = match.group(1)
            mp3_url = f"https://www.myinstants.com{mp3_path}"
            
            # Hanapin yung name sa katabi ng button
            parent = btn.parent
            name_tag = parent.find('a', class_='instant-link') if parent else None
            name = name_tag.text.strip() if name_tag else os.path.basename(mp3_path)
            
            safe_name = re.sub(r'[^a-zA-Z0-9_\-]', '', name.replace(' ', '_'))
            filename = f"{safe_name}.mp3"
            
            sounds.append({
                "name": name,
                "filename": filename,
                "url": mp3_url
            })
            
    return sounds

async def fetch_page(session, url, use_cffi=False):
    try:
        if use_cffi and CURL_CFFI_AVAILABLE:
            resp = await session.get(url, impersonate="chrome110", timeout=TIMEOUT)
            if resp.status_code == 200:
                return resp.text
        else:
            async with session.get(url, headers=HEADERS, timeout=TIMEOUT) as resp:
                if resp.status == 200:
                    return await resp.text()
    except Exception as e:
        print(f"Error fetching {url}: {e}")
    return None

async def download_file(session, url, filepath, use_cffi=False):
    try:
        if filepath.exists() and filepath.stat().st_size > 0:
            return "skipped"
            
        if use_cffi and CURL_CFFI_AVAILABLE:
            resp = await session.get(url, impersonate="chrome110", timeout=TIMEOUT)
            if resp.status_code == 200:
                with open(filepath, 'wb') as f:
                    f.write(resp.content)
                return "success"
        else:
            async with session.get(url, headers=HEADERS, timeout=TIMEOUT) as resp:
                if resp.status == 200:
                    with open(filepath, 'wb') as f:
                        while True:
                            chunk = await resp.content.read(8192)
                            if not chunk:
                                break
                            f.write(chunk)
                    return "success"
    except Exception:
        pass
    return "error"

async def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    
    if CURL_CFFI_AVAILABLE:
        console.print("[bold green]✓ curl_cffi detected! WAF Bypass (TLS Spoofing) is ACTIVE.[/bold green]")
        session_class = AsyncSession
        use_cffi = True
    else:
        console.print("[bold yellow]! curl_cffi not found. Falling back to standard aiohttp.[/bold yellow]")
        session_class = aiohttp.ClientSession
        use_cffi = False

    console.print(f"[bold cyan]Starting Deep Scrape for '{TARGET_QUERY}'...[/bold cyan]")
    
    all_sounds = []
    page = 1
    sem = asyncio.Semaphore(CONCURRENT_REQUESTS)
    
    if use_cffi:
        session = AsyncSession()
    else:
        session = aiohttp.ClientSession()

    try:
        async def process_page(p):
            async with sem:
                url = f"https://www.myinstants.com/en/search/?name={quote(TARGET_QUERY)}&page={p}"
                html = await fetch_page(session, url, use_cffi)
                if html:
                    sounds = parse_sounds(html)
                    await asyncio.sleep(random.uniform(1.0, 2.0))
                    return sounds
                return []

        while page <= MAX_PAGES:
            batch_pages = range(page, min(page + 3, MAX_PAGES + 1))
            console.print(f"[*] Scanning pages {page} to {page+2}...")
            
            results = await asyncio.gather(*(process_page(p) for p in batch_pages))
            
            batch_found = 0
            for res in results:
                all_sounds.extend(res)
                batch_found += len(res)
                
            if batch_found == 0:
                console.print("[yellow]No sounds found in this batch. Stopping.[/yellow]")
                break
                
            page += 3

    finally:
        await session.close()

    unique_sounds = {s['filename']: s for s in all_sounds}.values()
    unique_sounds = list(unique_sounds)
    
    console.print(f"\n[bold green]✓ Total unique sounds found: {len(unique_sounds)}[/bold green]")
    
    if not unique_sounds:
        console.print("[red]Walang nakuha. Baka na-block ka ng Cloudflare.[/red]")
        return

    json_file = OUTPUT_DIR / "sounds_data.json"
    with open(json_file, 'w') as f:
        json.dump(unique_sounds, f, indent=2)
    console.print(f"[✓] Data saved to: {json_file}")

    console.print(f"\n[*] Downloading to {OUTPUT_DIR}...")
    
    if use_cffi:
        session = AsyncSession()
    else:
        session = aiohttp.ClientSession()

    try:
        for i, s in enumerate(unique_sounds, 1):
            filepath = OUTPUT_DIR / s['filename']
            status = await download_file(session, s['url'], filepath, use_cffi)
            
            if status == "success":
                console.print(f"  [green]✓ ({i}/{len(unique_sounds)}) {s['filename']}[/green]")
            elif status == "skipped":
                console.print(f"  [dim]= ({i}/{len(unique_sounds)}) Skipped[/dim]")
            else:
                console.print(f"  [red]✗ ({i}/{len(unique_sounds)}) Failed: {s['filename']}[/red]")
                
            await asyncio.sleep(random.uniform(0.5, 1.0))
    finally:
        await session.close()

    console.print(f"\n[bold green]✓ All done! Files saved in: {OUTPUT_DIR}[/bold green]")

if __name__ == "__main__":
    asyncio.run(main())
