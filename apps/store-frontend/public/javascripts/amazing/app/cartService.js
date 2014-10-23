(function () {

    //TODO gérer la concurrence
    var currentCart;

    function loadCart(){
        return http.get(env.cartUrl);
    }

    function getCurrentCart(){
        var deferred = new jQuery.Deferred();
        if(currentCart){
            deferred.resolve(currentCart);
        }else{
            loadCart().done(function(cart){
                currentCart = cart;
                console.log("CURRENT CART", currentCart);
                deferred.resolve(cart);
            }, function(error){
                deferred.reject("Error");
            });
        }
        return deferred.promise();
    }

    var onCartAdded = [];
    var onQuantityUpdated = [];
    var onProductDeleted = [];
    var onCartOrdered = [];
    var onErrorsOrder = [];

    function handleMessage(msg){
        if(msg.type === "ProductQuantityUpdated") {
            for(var i in onQuantityUpdated){
                onQuantityUpdated[i](msg.message);
            }
        } else if (msg.type === "CartOrdered") {
            for(var j in onCartOrdered){
                onCartOrdered[j](msg.message);
            }
        } else if (msg.type === "ErrorValidatingCart") {
            for(var k in onCartOrdered){
                onErrorsOrder[k](msg.message);
            }
        }
    }

    function sendMessage(msg){
        if(socket){
           console.log("Sending MSG : ",JSON.stringify(msg));
           socket.send(JSON.stringify(msg));
        } else if(!contextService.hasContext()){
            window.location = env.identityUrl + '?service=' + window.location;
        }
    }
    var source;
    var socket;
    cartService = {
        loadCart: function() {
            return loadCart();
        },
        getCurrentCart : function(){
            return getCurrentCart();
        },
        addToCart: function(idProduct, quantity){
            getCurrentCart().done(function(currentCart){
                var data = {
                    type : "AddProductToCart",
                    message : {
                      idCart: currentCart.id,
                      idProduct : idProduct,
                      quantity : quantity
                    }
                };
                sendMessage(data);
            });
        },
        removeFromCart: function(idProduct, quantity){
            getCurrentCart().done(function(currentCart){
                var data = {
                    type : "RemoveProductFromCart",
                    message : {
                      idCart: currentCart.id,
                      idProduct : idProduct,
                      quantity : quantity
                    }
                };
                sendMessage(data);
            });
        },
        orderCart : function(){
            getCurrentCart().done(function(currentCart){
                sendMessage({type:"OrderCart", message:{idCart: currentCart.id}});
            });
        },
        getHistory: function(){
            return http.get(env.cartHistoryUrl);
        },
        onCartCreated : function(callback){
            onCartAdded.push(callback);
        },
        onQuantityUpdated : function(callback){
            onQuantityUpdated.push(callback);
        },
        onCartOrdered : function(callback){
            onCartOrdered.push(callback);
        },
        onErrorsOrder : function(callback){
            onErrorsOrder.push(callback);
        },
        startWs: function(){
            try{
                socket = new WebSocket("ws://cart.amazing.com/cart/ws");
            }catch(e){
                console.log("TRY ERROR", e);
            }
            socket.onopen = function(e){
                console.log("OPEN", e);
            };
            socket.onmessage = function(e){
                console.log("MESSAGE", e);
                handleMessage(JSON.parse(e.data));
            };
            socket.onclose = function(e){
                console.log("CLOSE", e);
            };
            socket.onerror = function(e){
                console.log("ERROR", e);
            };
        }
    };

})();