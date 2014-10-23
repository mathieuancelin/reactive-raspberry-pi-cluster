define(['jquery', 'ordersService', 'handlebars'], function (jquery, ordersService, Handlebars) {

    var tpl = Handlebars.compile($("#tplLine").html());


    ordersService.onNewOrders(function(msg){
        msg.at = new Date(msg.at).format('{dd}/{MM}/{yyyy} {hh}:{mm}:{ss}');
        $(tpl(msg)).prependTo("table tbody").hide().fadeIn(1000);
    });

});