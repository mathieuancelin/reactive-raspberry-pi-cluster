(function () {
    var source ;
    var onProductUpdated = [];
    productService = {
        startEventSource: function(){
            if(typeof(EventSource) !== "undefined") {
                source = new EventSource(env.productFeedUrl);
                source.addEventListener('message', function(e) {
                    console.log("SSE, new message", e);
                    for(var i in onProductUpdated){
                        onProductUpdated[i](JSON.parse(e.data));
                    }
                }, false);
                source.addEventListener('open', function(e) {
                    console.log("SSE OPEN !!!!");
                }, false);
                source.addEventListener('error', function(e) {
                    console.log("SSE ERROR", e);
                }, false);
            } else {
                console.log("Pas de support sse ");
            }
        },
        onProductUpdated : function(callback){
            onProductUpdated.push(callback);
        }

    };
    productService.startEventSource();
})();