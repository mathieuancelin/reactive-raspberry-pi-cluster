(function () {

    //TODO gérer la concurrence
    var currentContext;

    contextService = {
        hasContext:  function(){
            if(currentContext){
                return true;
            } else {
                return false;
            }
        },
        loadContext: function(callback){
            var deferred = new jQuery.Deferred();
            if(currentContext){
                deferred.resolve(currentContext);
            } else {
                $.ajax({
                   url : env.contextUrl,
                   dataJson: 'json',
                   xhrFields: {
                       withCredentials: true
                   },
                   statusCode : {
                    401: function(){},
                    403: function(){}
                   }
                }).success(function(data){
                    deferred.resolve(data);
                }).error(function(error){
                    deferred.reject("Error");
                });
            }
            return deferred.promise();
        }
    };
})();