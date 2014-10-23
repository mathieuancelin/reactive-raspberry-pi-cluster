(function () {

    contextService.loadContext().done(function (context){
        if(context){
            $.get('/templates/bar.html').success(function(html){
                var tpl = Handlebars.compile(html);
                $("#menu").html(tpl(context));
                cartService.startWs();
                function updateCart(){
                    cartService.loadCart().done(function(cart){
                        var count = cart.count;
                        $("#cart").html("<i class=\"fi-shopping-cart\"></i> "+count+" items");
                    });
                }
                updateCart();
                cartService.onQuantityUpdated(function(msg){
                    updateCart();
                });
            });
        }
    });

})();