(function () {

    cartService.loadCart().done(function(cart){
        var ids = [];
        var mapIdItem = {};
        for (var line in cart.items){
            ids.push(cart.items[line].productId);
            mapIdItem[cart.items[line].productId] = cart.items[line];
        }
        var items = {};
        $.get(env.cartFragments(ids)).success(function(products){
           var cartHtml = _(products)
               .map(function(prod){
                    var id = prod.id;
                    var item = new Item(id, prod.price, mapIdItem[id].quantity, prod.html);
                    items[id] = item;
                    return item.getHtml();
               })
               .join(" ");
           $("#cartTable table tbody").html(cartHtml);
           $(".cart-product").each(function(){
               var fragment = $(this);
               var id = getId(fragment.attr("id"));
                fragment.find(".more").click(function(){
                    cartService.addToCart(id, 1);
                });
                fragment.find(".less").click(function(){
                    cartService.removeFromCart(id, 1);
                });
           });
           $("#cartTable table tbody").show();
        });
        $("#validateCart").click(function(){
            cartService.orderCart();
        });
        cartService.onCartOrdered(function(msg){
            $("#cartTable > table > tbody").html("");
            loadHistory();
        });
        cartService.onQuantityUpdated(function(msg){
            var id = msg.productId;
            var item = items[id];
            var qte = msg.quantity;
            item.setQuantity(qte);
            var html = item.getHtml();
            updateHtml(id, html);
        });
        cartService.onErrorsOrder(function(msg){
            var _template = Handlebars.compile($("#alertTemplate").html());
            $("#errors").html(_template({message: "An error occured during cart ordering. Please try later."}));

        });

        function updateHtml(id, html){
            $("#cart-"+id).replaceWith(html);
            $("#cart-"+id+" .more").click(function(){
                cartService.addToCart(id, 1);
            });
            $("#cart-"+id+" .less").click(function(){
                cartService.removeFromCart(id, 1);
            });
         }

    });


    function Item(id, price, quantity, template){
        var _template = Handlebars.compile(template);
        var model = {
           id: id,
           price: price,
           quantity: quantity,
           total:quantity * price
        };
        this.setQuantity = function(qte){
            model.quantity = qte;
            model.total = qte * model.price;
        };
        this.getHtml = function(){
            return _template(model);
        };

    }

    var tpl = Handlebars.compile("<ul>{{#each orders}}<li>{{at}}<ul>{{#each lines}}<li>{{id}} - qte : {{quantity}} - prix : {{unitPrice}}</li>{{/each}}</ul></li>{{/each}}</ul>");

    function loadHistory(){
        cartService.getHistory().done(function(history){
            console.log("history",history);
            if(history){
                $("#history-cart").html(tpl(history));
            }
        });
    }

    function getId(id){
        return id.substring("cart-".length, id.length);
    }
    loadHistory();

})();