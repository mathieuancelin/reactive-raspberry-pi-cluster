(function () {
    env = {
          identityUrl:'http://identity.amazing.com',
          contextUrl:'http://identity.amazing.com/api/users/current',
          cart:'http://cart.amazing.com',
          cartFragments: function(ids){
            return 'http://www.amazing.com/fragments/cart?ids='+ids.toString();
          },
          seedCart:'http://cart.amazing.com/cart/seed',
          cartUrl: 'http://cart.amazing.com/api/cart',
          cartHistoryUrl: 'http://cart.amazing.com/api/orders',
          addToCartUrl: function(id){
            return 'http://cart.amazing.com/api/cart/' + id;
          },
          productFeedUrl : '/products/feed'
      };
})();