$(function() {

    try {  //hack for HTMLUnit
        var idParam = new URLSearchParams(location.search.slice(1)).get('id');
    } catch(e) {
        delete idParam
    }

    var table = $("#data-table").DataTable( {
        paging: false,
        dom: 'rit',
        initComplete: function () {
            this.api().columns().every( function () {
                var column = this;
                if($(this.header()).hasClass('filter:select')) {
                    var select = $('<select><option value=""></option></select>')
                        .appendTo( $(column.footer()).empty() )
                        .on( 'change', function () {
                            var val = $.fn.dataTable.util.escapeRegex($(this).val());

                            column.search( val ? '^'+val+'$' : '', true, false )
                                  .draw();
                        } );

                    column.data().unique().sort().each(function (d, j) {
                        if(d) {
                            select.append('<option value="'+d+'">'+d+'</option>')
                        }
                    });

                } else if($(this.header()).hasClass('filter:text')) {
                    var text = $('<input type="text"></input')
                        .appendTo( $(column.footer()).empty() )
                        .on( 'keyup', function() {
                            var val = $.fn.dataTable.util.escapeRegex($(this).val());
                            column.search( val, true, false )
                                  .draw();
                        });
                }
            });
         }
     });

     if(idParam) {
        var column = table.columns(findIndexColumn());

        $(column.footer()).find("input").val(idParam);
        column.search(idParam, true, false).draw();
     }
});

function findIndexColumn() {
    var table = $("#data-table");
    var indexColumn = table.find(".id");
    var allColumns = table.find("th");

    return allColumns.index(indexColumn);
}