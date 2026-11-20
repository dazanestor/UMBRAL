package es.qvadis.app.model

import org.json.JSONArray

/** Una llamada reciente del registro de la placa (/api/calls). */
data class CallEntry(
    val ts: Long,            // epoch ms
    val outcome: String,     // sonando | atendida | perdida | dnd | foto
    val photoFile: String,   // nombre del fichero de captura ("" si no hay)
    val photoUrl: String     // URL completa para pintar la foto ("" si no hay)
) {
    val answered: Boolean get() = outcome == "atendida"

    /** Etiqueta legible del estado. */
    fun label(): String = when (outcome) {
        "atendida" -> "Atendida"
        "perdida" -> "Perdida"
        "sonando" -> "Sonando…"
        "dnd" -> "No molestar"
        "foto" -> "Foto"
        else -> outcome
    }

    companion object {
        /**
         * Parsea el JSON de /api/calls de la placa. Formato real:
         * [ { "ts":169..., "type":"timbre", "outcome":"atendida", "photo":"snap_xxx.jpg" }, ... ]
         * @param urlFor construye la URL de una foto a partir de su nombre de fichero.
         */
        fun parseList(json: String, urlFor: (String) -> String): List<CallEntry> {
            val out = ArrayList<CallEntry>()
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val photo = o.optString("photo", "")
                    out.add(
                        CallEntry(
                            ts = o.optLong("ts", 0L),
                            outcome = o.optString("outcome", ""),
                            photoFile = photo,
                            photoUrl = if (photo.isNotBlank()) urlFor(photo) else ""
                        )
                    )
                }
            }
            return out.sortedByDescending { it.ts }   // más recientes primero
        }
    }
}
