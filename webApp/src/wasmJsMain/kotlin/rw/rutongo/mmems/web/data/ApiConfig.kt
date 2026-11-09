package rw.rutongo.mmems.web.data

@JsFun("() => globalThis.RUTONGO_API_BASE || 'http://localhost:8080'")
external fun apiBaseUrl(): String
