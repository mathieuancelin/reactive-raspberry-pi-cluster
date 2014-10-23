(function () {

    http  = {
        ajaxSecured : function(obj) {
            var deferred = new jQuery.Deferred();
            var call = {
                  type : obj.type,
                  url : obj.url,
                  xhrFields: {
                      withCredentials: true
                  },
                  statusCode : {
                   401: function(){
                        window.location = env.identityUrl + '?service=' + window.location;
                   },
                   403: function(){
                        window.location = env.identityUrl + '?service=' + window.location;
                   }
                  }
               };
            if(obj.data){
                call.data = obj.data;
                dataType = 'json';
            }
            $.ajax(call).success(function(data){
                deferred.resolve(data);
            }).error(function(error){
                deferred.reject("Error");
            });
            return deferred.promise();
        },
        get: function(url){
            return http.ajaxSecured({url:url, type:'GET'});
        },
        post: function(url, obj){
            return http.ajaxSecured({url:url, type:'POST', data: obj});
        },
        put: function(url, obj){
            return http.ajaxSecured({url:url, type:'PUT', data: obj});
        }
    };
})();