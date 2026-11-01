#!/usr/bin/env python3
"""
PlaySoundboard Scraper
Runtime: Python 3.10+
Dependencies: aiohttp, beautifulsoup4, rich
"""

import asyncio
import aiohttp
import os
import re
import json
from pathlib import Path
from bs4 import BeautifulSoup

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
# Palitan mo 'to kung gusto mo ibang sound (e.g., "meme", "bruh", "pinoy")
SEARCH_QUERY = "pinoy" 
DOWNLOAD_DIR = Path("/storage/emulated/0/Download/pinoy_sounds")
BASE_URL = "https://www.playsoundboard.com"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
}

async def fetch_html(session, url):
    try:
        async with session.get(url, headers=HEADERS, timeout=15) as resp:
            if resp.status == 200:
                return await resp.text()
    except Exception as e:
        console.print(f"[red]Error fetching {url}: {e}[/red]")
    return None

def extract_mp3_links(html, page_url):
    """Hanapin ang direct mp3 links sa HTML."""
    if not html:
        return []
    
    soup = BeautifulSoup(html, 'html.parser')
    sounds = []
    
    # Playsoundboard often uses <audio> tags or data attributes for the source
    # We also scan for any string ending in .mp3
    
    # 1. Look for <audio> tags
    for audio in soup.find_all('audio'):
        src = audio.get('src')
        if src and src.endswith('.mp3'):
            full_url = src if src.startswith('http') else BASE_URL + src
            name = full_url.split('/')[-1].replace('.mp3', '').replace('%20', ' ')
            sounds.append({"name": name, "url": full_url, "filename": f"{name}.mp3"})
            
    # 2. Look for data-audio attributes
    for tag in soup.find_all(attrs={"data-audio": True}):
        src = tag['data-audio']
        if src.endswith('.mp3'):
            full_url = src if src.startswith('http') else BASE_URL + src
            name = tag.get('data-name', full_url.split('/')[-1].replace('.mp3', '').replace('%20', '_'))
            sounds.append({"name": name, "url": full_url, "filename": f"{name}.mp3"})
            
    # 3. Regex Fallback (hanapin kahit saan sa JS/HTML)
    # Pattern: https://www.playsoundboard.com/.../name.mp3
    mp3_pattern = re.compile(r'(https?://[^\s"\'<>]+?\.mp3)')
    matches = mp3_pattern.findall(html)
    for match in matches:
        # Clean up trailing characters if regex accidentally grabbed some
        match = match.strip('\\')
        name = match.split('/')[-1].replace('.mp3', '').replace('%20', '_')
        sounds.append({"name": name, "url": match, "filename": f"{name}.mp3"})
        
    # Deduplicate by URL
    unique_sounds = {s['url']: s for s in sounds}.values()
    return list(unique_sounds)

async def download_file(session, url, filepath):
    try:
        if filepath.exists() and filepath.stat().st_size > 0:
            return "skipped"
            
        async with session.get(url, headers=HEADERS, timeout=20) as resp:
            if resp.status == 200:
                with open(filepath, 'wb') as f:
                    while True:
                        chunk = await resp.content.read(8192)
                        if not chunk:
                            break
                        f.write(chunk)
                return "success"
            return f"status {resp.status}"
    except Exception as e:
        return str(e)[:20]

async def main():
    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
    
    console.print(f"[bold cyan]Scraping PlaySoundboard for '{SEARCH_QUERY}'...[/bold cyan]")
    
    async with aiohttp.ClientSession() as session:
        # Step 1: Fetch search results
        # Playsoundboard search URL format
        search_url = f"{BASE_URL}/search?q={SEARCH_QUERY}"
        html = await fetch_html(session, search_url)
        
        if not html:
            console.print("[red]Failed to fetch search page. Try again later.[/red]")
            return
            
        sounds = extract_mp3_links(html, search_url)
        
        if not sounds:
            console.print("[yellow]No MP3 links found on the search page.[/yellow]")
            console.print("[dim]Baka kailangan i-adjust yung regex or parser para sa site nila.[/dim]")
            return
            
        console.print(f"[green]✓ Found {len(sounds)} direct MP3 links![/green]")
        
        # Step 2: Save JSON
        json_file = DOWNLOAD_DIR / "soundboard_data.json"
        with open(json_file, 'w') as f:
            json.dump(sounds, f, indent=2)
        console.print(f"[✓] Data saved to: {json_file}")
        
        # Step 3: Download
        console.print(f"\n[bold cyan]Downloading to {DOWNLOAD_DIR}...[/bold cyan]")
        
        if RICH_AVAILABLE:
            with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), BarColumn(), TimeElapsedColumn()) as progress:
                task = progress.add_task("[cyan]Downloading...", total=len(sounds))
                
                for i, s in enumerate(sounds, 1):
                    filepath = DOWNLOAD_DIR / s['filename']
                    progress.update(task, description=f"[cyan]({i}/{len(sounds)}) {s['filename'][:20]}...")
                    
                    status = await download_file(session, s['url'], filepath)
                    if status == "success":
                        console.print(f"  [green]✓ {s['filename']}[/green]")
                    elif status == "skipped":
                        console.print(f"  [dim]= Skipped: {s['filename']}[/dim]")
                    else:
                        console.print(f"  [red]✗ Failed: {s['filename']} ({status})[/red]")
                        
                    progress.advance(task)
                    await asyncio.sleep(0.5)
        else:
            for s in sounds:
                status = await download_file(session, s['url'], DOWNLOAD_DIR / s['filename'])
                print(f"{status.ljust(8)} | {s['filename']}")

    console.print(f"\n[bold green]✓ All done![/bold green]")

if __name__ == "__main__":
    asyncio.run(main())
