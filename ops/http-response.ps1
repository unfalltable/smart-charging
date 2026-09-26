function Convert-HttpContentToText {
    param([AllowNull()][object]$Content)

    if ($null -eq $Content) { return '' }
    if ($Content -is [string]) { return $Content }
    if ($Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($Content) }
    if ($Content -is [Array]) {
        try { return [Text.Encoding]::UTF8.GetString([byte[]]$Content) }
        catch { }
    }
    return [Convert]::ToString($Content, [Globalization.CultureInfo]::InvariantCulture)
}
