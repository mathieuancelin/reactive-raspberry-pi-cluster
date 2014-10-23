define(['jquery'], function (jquery) {

    var ordersService = {
        startWs: function(){
            var _this = this;
            try{
                _this.socket = new WebSocket("ws://backend.amazing.com/orders/ws");
                _this.socket.onopen = function(e){
                    console.log("OPEN", e);
                };
                _this.socket.onmessage = function(e){
                    console.log("MESSAGE", e);
                    _this.handleMessage(JSON.parse(e.data))
                };
                _this.socket.onclose = function(e){
                    console.log("CLOSE", e);
                };
                _this.socket.onerror = function(e){
                    console.log("ERROR", e);
                };
            }catch(e){
                console.log("TRY ERROR", e);
            }
        },

        handleMessage: function(message) {
            if(message.type === "NewOrder") {
                this.onNewOrdersFn.each(function(fn){
                    fn(message.message);
                });
            }
        },

        onNewOrders: function(callback) {
            if(!this.onNewOrdersFn){
                this.onNewOrdersFn = _(new Array());
            }
            this.onNewOrdersFn.push(callback);
        }
    }
    ordersService.startWs();
    return ordersService;
});