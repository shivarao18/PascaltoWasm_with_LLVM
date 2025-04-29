program TestWhileLoopWithBreakAndContinue;
var
    I: Integer;
begin
    I := 0;
    while I < 10 do
    begin
        I := I + 1;
        IF I = 3 THEN
        BEGIN
            WRITELN('in if');
        END;
        WRITELN(I);
    end;
end.