(function () {

    $(".product-detail").each(function(){
        var fragment = $(this);
        var id = fragment.attr("id");
        var productId = id.substring("product-".length-1, id.length);
        fragment.find(".addToCart").click(function(){
            cartService.addToCart(productId,1);
        });

    });

    productService.onProductUpdated(function(p){
        var id = p.id;
        var html = _(p.fragments).find(function(frag){return frag.type === 'recherche';});
        if(html){
            if($("#detail-"+id).length > 0){
                $("#detail-"+id).remove();
            }
            $(html.html).prependTo(".products").hide().fadeIn(1000);

            $("#detail-"+id+" .addToCart").click(function(){
                cartService.addToCart(productId,1);
            });
        }
    });

})();