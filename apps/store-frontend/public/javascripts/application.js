
$(document).ready(function(){
    $("#searchButton").click(function(){
        var url = "/search?search="+ $("#searchInput").val();
        document.location.href = url;
    });
});
