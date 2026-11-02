#!/usr/bin/env python3
"""
MyInstants Bulk Profile Extractor
Runtime: Python 3.10+
Dependencies: aiohttp, beautifulsoup4, rich
"""

import asyncio
import aiohttp
import json
import re
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
INPUT_FILE = "mp3_links.txt"  # Galing sa listahan mo
OUTPUT_FILE = "/storage/emulated/0/Download/uploader_profiles.json"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Accept-Language": "en-US,en;q=0.9"
}

# ==========================================
# LOGIC
# ==========================================

async def get_uploader(session, url):
    """Fetch page and extract uploader username."""
    # Palitan ang media URL papuntang instant page URL
    # Example: https://www.myinstants.com/media/sounds/silly-laugh_x4pBItl.mp3
    # To: https://www.myinstants.com/en/instant/silly-pinoy-laugh-19103/ (kailangan natin i-search)
    
    # Since media links don't have uploader, we need to guess the instant page
    # Alternatively, we can just use the MyInstants API or search.
    # Actually, the instant page URL is different from the media file name.
    # Best approach: Use search to find the sound, then get uploader.
    
    # Let's try a direct approach first: try to query the search API for the filename
    filename = url.split('/')[-1].replace('.mp3', '')
    search_query = filename.replace('-', ' ').replace('_', ' ')
    
    search_url = f"https://www.myinstants.com/en/search/?name={search_query}"
    
    try:
        async with session.get(search_url, headers=HEADERS, timeout=15) as resp:
            if resp.status == 200:
                html = await resp.text()
                soup = BeautifulSoup(html, 'html.parser')
                
                # Find the first instant link
                instant_link = soup.find('a', class_='instant-link')
                if instant_link:
                    instant_url = instant_link.get('href')
                    if not instant_url.startswith('http'):
                        instant_url = f"https://www.myinstants.com{instant_url}"
                    
                    # Fetch the instant page
                    async with session.get(instant_url, headers=HEADERS, timeout=15) as page_resp:
                        if page_resp.status == 200:
                            page_html = await page_resp.text()
                            page_soup = BeautifulSoup(page_html, 'html.parser')
                            
                            # Look for uploader info (usually in a meta tag or a link)
                            # MyInstants usually shows "Uploaded by [username]" or similar
                            # Let's check common patterns
                            
                            # Pattern 1: Link to profile
                            profile_link = page_soup.find('a', href=re.compile(r'/en/profile/'))
                            if profile_link:
                                return profile_link.text.strip()
                            
                            # Pattern 2: Meta tag or hidden text
                            # We might need to inspect the page structure
                            # For now, return 'Unknown'
                            return "Unknown"
            return "Not Found"
    except Exception as e:
        return "Error"

async def main():
    print("[*] Bulk Profile Extractor starting...")
    
    # Read links
    links = []
    try:
        with open(INPUT_FILE, 'r') as f:
            for line in f:
                line = line.strip()
                if line and line.startswith('http'):
                    links.append(line)
    except FileNotFoundError:
        print(f"[✗] File not found: {INPUT_FILE}")
        print("[!] Please create a text file with one MP3 link per line.")
        return
        
    print(f"[*] Loaded {len(links)} links.")
    
    results = {}
    
    async with aiohttp.ClientSession() as session:
        if RICH_AVAILABLE:
            with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), BarColumn(), TimeElapsedColumn()) as progress:
                task = progress.add_task("[cyan]Extracting profiles...", total=len(links))
                
                for link in links:
                    # Get filename for display
                    filename = link.split('/')[-1]
                    progress.update(task, description=f"[cyan]{filename[:20]}...")
                    
                    uploader = await get_uploader(session, link)
                    results[link] = uploader
                    
                    progress.advance(task)
                    await asyncio.sleep(0.5)  # Delay
        else:
            for link in links:
                uploader = await get_uploader(session, link)
                results[link] = uploader
                await asyncio.sleep(0.5)
                
    # Save results
    try:
        with open(OUTPUT_FILE, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"\n[✓] Profiles saved to: {OUTPUT_FILE}")
    except Exception as e:
        print(f"[✗] Failed to save file: {e}")

if __name__ == "__main__":
    asyncio.run(main())
