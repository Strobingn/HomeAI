"""Plain-language sentences returned to the phone and printed on the PC."""


def unreachable_message(url: str) -> str:
    return (
        f"Ollama is not answering at {url}. "
        "If it is already installed, open the Ollama app on the PC. "
        "Alternatively, open a separate PowerShell or terminal window and run: ollama serve. "
        "That starts the local model service, normally on http://127.0.0.1:11434; leave that window open. "
        "Check it with: ollama list. If serve says the address is already in use, another Ollama service may already be running. "
        "Check OLLAMA_BASE_URL in config/.env matches that service, then restart Desk and test the phone connection again."
    )


def missing_model_message(model: str) -> str:
    return f"{model} is not downloaded. On this PC run: ollama pull {model}"


def chat_stopped_message() -> str:
    return "The model stopped before it finished. Look at the server window on the PC, then send the message again."

