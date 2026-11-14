# Bounded242 cache delivery

The updater previously retained unbounded `OutBuffer` instances twice per archive
(priority and normal), grew them for continuation markers, and copied each response
again before sending. Large mapped assets exhausted its256MiB staging heap.

`CacheManager` now retains immutable, exactly sized encoded responses in an
access-ordered LRU. Priority and normal requests share the same `(index, group)`
key because their184 response bytes are identical. The default budget is the
smaller of32MiB and one eighth of the JVM maximum heap, with a4MiB minimum.
`-Dkronos.js5.cacheBytes` may lower or disable it. An8,192-entry limit also bounds
metadata for tiny responses. Responses larger than the budget are served without
retention. Replacing the file-store identity clears the cache.

Framing allocates exactly eight header bytes,504 initial payload bytes and then
one255 marker per subsequent block of up to511 payload bytes. Container revision
trailers are omitted as before. Unencrypted writes use independent read-only
Netty views over the cached bytes. XOR makes one private exact-size copy; the
legacy master-reference XOR exemption remains unchanged.

`Js5Decoder` retains at most512 request descriptors and writes one response at a
time per channel, so a slow client cannot pin400 independently encoded archives
in the outbound queue. It captures each request's XOR key at receipt. Write
failure/closed channels discard the queue; overflowing requests close that
connection. The standard184 client's200 priority plus200 normal requests fit.

The public `FileStore(String)` constructor retains its original limits. An
explicit overload permits a bounded8MiB compressed-container limit only for
index7 model-bank groups65408..65534. All other groups retain their original
limits. Main updater startup and the isolated test entry use the extension only
after validating the184242 cache marker. Neither the game login protocol nor the
shared `OutBuffer` implementation was changed.

`Test-Js5Cache.ps1` passes4,441 detached checks under `-Xmx128m`: byte-for-byte
comparison with the original native encoder, continuation-boundary roundtrips,
compressed/uncompressed header handling, immutable/XOR isolation, LRU accounting,
96 large5.55MB response replacements,150 concurrent zero-copy views,400 pipelined
requests, failure/overflow cleanup, and a native1.08MB bank disk-to-wire fixture.
The fixture verifies the legacy1MB rejection and preserves the same rejection
for ordinary index7 groups and other indexes. It opens no server listener.

This test is separate from actual staged network CRC checks and actual-client
asset loading. Those must use freshly built updater/API jars and a freshly
compiled `Content242CacheServer` helper; an old helper still selects the original
one-argument file-store constructor.
