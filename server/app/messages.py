"""Plain-language sentences returned to the phone and printed on the PC."""


def unreachable_message(url: str) -> str:
    return (
        f"Ollama is not answering at {url}. "
        "It is already installed. Open the Ollama app on this PC, or run: ollama serve"
    )


def missing_model_message(model: str) -> str:
    return f"{model} is not downloaded. On this PC run: ollama pull {model}"


def chat_stopped_message() -> str:
    return "The model stopped before it finished. Look at the server window on the PC, then send the message again."
