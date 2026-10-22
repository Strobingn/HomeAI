package com.homedesk.chat

import androidx.core.content.FileProvider

/** An app-owned provider avoids direct FileProvider instantiation on OEM builds. */
class UpdateFileProvider : FileProvider()
