"""Short-lived, credential-free metasearch worker, called by the gateway."""
import json
import sys


def main() -> None:
    from ddgs import DDGS
    body = json.loads(sys.stdin.read(8192))
    query = body["query"]
    if not isinstance(query, str) or not 3 <= len(query) <= 500:
        raise ValueError("Invalid query")
    DDGS.threads = 2
    with DDGS(timeout=6, verify=True) as search:
        rows = search.text(query, max_results=5, safesearch="off", backend="duckduckgo,bing")
    bounded = [{"title": str(row.get("title", ""))[:150], "url": str(row.get("href", ""))[:1000],
                "content": str(row.get("body", ""))[:700]} for row in rows[:5] if isinstance(row, dict)]
    print(json.dumps(bounded, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit(1)
