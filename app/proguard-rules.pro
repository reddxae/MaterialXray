# UdsChannelBuilder reflects into grpc-okhttp to create Unix domain socket channels.
-keepclassmembers,allowoptimization class io.grpc.okhttp.OkHttpChannelBuilder {
    public static io.grpc.okhttp.OkHttpChannelBuilder forTarget(java.lang.String, io.grpc.ChannelCredentials);
    public io.grpc.okhttp.OkHttpChannelBuilder socketFactory(javax.net.SocketFactory);
}
