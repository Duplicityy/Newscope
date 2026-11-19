package local.kronos.mobile;

/** This independent owner build has one destination. Authentication is still performed by the server. */
final class OwnerEndpoint {
    static final String HOST=BuildConfig.OWNER_TEST_HOST;
    static final int CACHE_PORT=7304, GAME_PORT=13302;
    static final String PROTOCOL="wire184 / login8 / movement6 / cache184729 / lobby-v3";
    private OwnerEndpoint(){}
}
