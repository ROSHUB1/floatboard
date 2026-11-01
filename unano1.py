#!/usr/bin/env python3
"""
Voicy.network Scraper
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
SEARCH_QUERY = "pinoy"
DOWNLOAD_DIR = Path("/storage/emulated/0/Download/pinoy_sounds")
BASE_URL = "https://www.voicy.network"

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

def extract_sound_links(html):
    """Hanapin ang mga link ng sounds sa search page."""
    if not html:
        return []
    
    soup = BeautifulSoup(html, 'html.parser')
    sounds = []
    
    # Voicy typically lists sounds as links like /sounds/<id>
    for a_tag in soup.find_all('a', href=True):
        href = a_tag['href']
        if '/sounds/' in href or '/sound/' in href:
            full_url = href if href.startswith('http') else BASE_URL + href
            
            # Extract name from the title or the text inside the tag
            name = a_tag.text.strip() or os.path.basename(href)
            
            # Linisin ang filename
            safe_name = re.sub(r'[^a-zA-Z0-9_\-]', '', name.replace(' ', '_'))
            
            sounds.append({
                "name": name,
                "page_url": full_url,
                "filename": f"{safe_name}.mp3"
            })
            
    # Deduplicate by page_url
    unique_sounds = {s['page_url']: s for s in sounds}.values()
    return list(unique_sounds)

def extract_mp3_from_page(html):
    """Kunin yung direct MP3 link mula sa individual sound page."""
    if not html:
        return None
    
    soup = BeautifulSoup(html, 'html.parser')
    
    # 1. Hanapin sa <audio> tags
    for audio in soup.find_all('audio'):
        src = audio.get('src')
        if src and src.endswith('.mp3'):
            return src if src.startswith('http') else BASE_URL + src
            
    # 2. Hanapin sa <source> tags
    for source in soup.find_all('source'):
        src = source.get('src')
        if src and src.endswith('.mp3'):
            return src if src.startswith('http') else BASE_URL + src
            
    # 3. Regex fallback (hanapin kahit saan sa HTML/JS)
    mp3_pattern = re.compile(r'(https?://[^\s"\'<>]+?\.mp3)')
    matches = mp3_pattern.findall(html)
    if matches:
        return matches[0].strip('\\')
        
    return None

async def process_page(session, sound_info):
    """Process a single sound page to get the MP3 link."""
    html = await fetch_html(session, sound_info['page_url'])
    if html:
        mp3_url = extract_mp3_from_page(html)
        if mp3_url:
            sound_info['url'] = mp3_url
            return sound_info
    return None

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
    
    console.print(f"[bold cyan]Scraping Voicy.network for '{SEARCH_QUERY}'...[/bold cyan]")
    
    async with aiohttp.ClientSession() as session:
        # Step 1: Fetch search results
        search_url = f"{BASE_URL}/search?q={SEARCH_QUERY}"
        html = await fetch_html(session, search_url)
        
        if not html:
            console.print("[red]Failed to fetch search page. Try again later.[/red]")
            return
            
        sounds = extract_sound_links(html)
        
        if not sounds:
            console.print("[yellow]No sound links found on the search page.[/yellow]")
            return
            
        console.print(f"[green]✓ Found {len(sounds)} sound pages![/green]")
        
        # Step 2: Fetch MP3 links from each sound page
        console.print("[cyan]Extracting direct MP3 links (this might take a moment)...[/cyan]")
        valid_sounds = []
        
        for i, sound in enumerate(sounds, 1):
            result = await process_page(session, sound)
            if result:
                valid_sounds.append(result)
                console.print(f"  [green]✓ ({i}/{len(sounds)}) Found MP3 for: {result['name']}[/green]")
            else:
                console.print(f"  [red]✗ ({i}/{len(sounds)}) Failed to find MP3 for: {sound['name']}[/red]")
            await asyncio.sleep(1) # Delay para iwas ban
            
        if not valid_sounds:
            console.print("[red]No direct MP3 links could be extracted.[/red]")
            return
            
        # Step 3: Save JSON
        json_file = DOWNLOAD_DIR / "voicy_data.json"
        with open(json_file, 'w') as f:
            json.dump(valid_sounds, f, indent=2)
        console.print(f"\n[✓] Data saved to: {json_file}")
        
        # Step 4: Download
        console.print(f"\n[bold cyan]Downloading {len(valid_sounds)} files to {DOWNLOAD_DIR}...[/bold cyan]")
        
        if RICH_AVAILABLE:
            with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), BarColumn(), TimeElapsedColumn()) as progress:
                task = progress.add_task("[cyan]Downloading...", total=len(valid_sounds))
                
                for s in valid_sounds:
                    filepath = DOWNLOAD_DIR / s['filename']
                    progress.update(task, description=f"[cyan]{s['filename'][:20]}...")
                    
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
            for s in valid_sounds:
                status = await download_file(session, s['url'], DOWNLOAD_DIR / s['filename'])
                print(f"{status.ljust(8)} | {s['filename']}")

    console.print(f"\n[bold green]✓ All done! Check MT Manager in Download/pinoy_sounds[/bold green]")

if __name__ == "__main__":
    asyncio.run(main())
